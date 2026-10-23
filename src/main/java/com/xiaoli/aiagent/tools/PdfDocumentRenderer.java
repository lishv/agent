package com.xiaoli.aiagent.tools;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.font.FontProvider;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 单次 PDF 渲染：排版 Markdown、校验图片归属、嵌入字体并检查生成文件。 */
final class PdfDocumentRenderer {

    private static final String FONT_FAMILY = "PdfChinese";

    /** A4 中文文档样式；代码保留缩进并换行，表格行尽量保持在同一页。 */
    private static final String CSS = """
            @page { size: A4; margin: 42pt; }
            body { font-family: PdfChinese; font-size: 11pt; line-height: 1.6; color: #222; }
            h1 { font-size: 22pt; }
            h2 { font-size: 17pt; }
            h3 { font-size: 14pt; }
            h1, h2, h3, h4, h5, h6 { margin: 14pt 0 8pt; page-break-after: avoid; }
            p { margin: 0 0 8pt; }
            ul, ol { margin: 6pt 0 10pt; padding-left: 22pt; }
            li { margin-bottom: 4pt; }
            a { color: #245a91; text-decoration: underline; }
            img { display: block; max-width: 100%; max-height: 220pt;
                  width: auto; height: auto; margin: 10pt auto; }
            .caption { display: block; text-align: center; font-size: 9pt;
                       color: #666; margin-bottom: 10pt; }
            .image-warning { color: #888; font-size: 9pt; }
            blockquote { margin: 8pt 0; padding-left: 12pt;
                         border-left: 3pt solid #bbb; color: #555; }
            table { width: 100%; border-collapse: collapse; margin: 10pt 0; }
            th, td { border: 0.5pt solid #ccc; padding: 5pt; font-size: 9pt; }
            th { background-color: #f2f2f2; }
            thead { display: table-header-group; }
            tr { page-break-inside: avoid; }
            code { font-family: PdfChinese; font-size: 9pt; background-color: #f5f5f5; }
            pre { white-space: pre-wrap; word-wrap: break-word; padding: 10pt;
                  margin: 8pt 0; background-color: #f5f5f5; border: 0.5pt solid #ddd; }
            pre code { background-color: transparent; }
            """;

    private final String regularFont;
    private final String boldFont;
    private final ToolFileStorage files;
    private final ToolContext context;
    private final List<String> warnings = new ArrayList<>();
    private int pageCount;

    /** 当前渲染实例仅用于一次生成，保存该次调用的字体、归属信息和结果。 */
    PdfDocumentRenderer(
            String regularFont, String boldFont, ToolFileStorage files, ToolContext context) {
        this.regularFont = regularFont;
        this.boldFont = boldFont;
        this.files = files;
        this.context = context;
    }

    /** 将内容写入尚未发布的文件，并在写入完成后检查 PDF 页数及页面内容流。 */
    void write(Path file, String content, List<String> imagePaths) throws IOException {
        String html = buildHtml(content, imagePaths);
        ConverterProperties properties = new ConverterProperties().setFontProvider(createFontProvider());
        try (PdfWriter writer = new PdfWriter(file.toString())) {
            HtmlConverter.convertToPdf(html, writer, properties);
        }
        try (PdfDocument pdf = new PdfDocument(new PdfReader(file.toString()))) {
            pageCount = pdf.getNumberOfPages();
            if (pageCount == 0) {
                throw new IOException("生成的 PDF 不包含页面，请检查文档内容");
            }
            for (int page = 1; page <= pageCount; page++) {
                pdf.getPage(page).getContentBytes();
            }
        }
    }

    /** 返回完整写入并通过解析校验的页数。 */
    int pageCount() {
        return pageCount;
    }

    /** 返回本次渲染中的图片提示，调用方不可修改内部列表。 */
    List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Markdown 转 HTML；仅替换正文实际引用的本地图片，不执行原始 HTML。 */
    private String buildHtml(String content, List<String> imagePaths) throws IOException {
        List<Extension> extensions = List.of(TablesExtension.create());
        Parser parser = Parser.builder().extensions(extensions).build();
        HtmlRenderer renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).build();
        Document html = Jsoup.parse(renderer.render(parser.parse(content)));
        Map<String, Path> images = buildImageIndex(imagePaths);
        for (Element image : html.select("img")) {
            replaceImage(image, images);
        }
        html.head().appendElement("meta").attr("charset", "UTF-8");
        html.head().appendElement("style").text(CSS);
        return html.outerHtml();
    }

    /** 将已提供的图片引用转成本地 URI；缺失图片保留可见占位文字和返回提示。 */
    private void replaceImage(Element image, Map<String, Path> images) {
        String source = image.attr("src").replace('\\', '/');
        Path path = images.get(source);
        if (path == null) {
            // Markdown 会编码空格和中文路径；解码只用于匹配已通过归属校验的图片。
            try {
                path = images.get(UriUtils.decode(source, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // 非法编码按未匹配图片处理，避免单张图片影响整份文档生成。
            }
        }
        if (path == null) {
            warnings.add("图片引用未匹配下载文件：" + source);
            image.before(new Element("span").addClass("image-warning")
                    .text("【图片未提供：" + image.attr("alt") + "】"));
            image.remove();
            return;
        }
        image.attr("src", path.toUri().toASCIIString());
        if (!image.attr("alt").isBlank()) {
            image.after(new Element("span").addClass("caption").text(image.attr("alt")));
        }
    }

    /** 校验图片归属，并建立文件名、调用方路径和实际路径的对应关系。 */
    private Map<String, Path> buildImageIndex(List<String> imagePaths) throws IOException {
        Map<String, Path> images = new HashMap<>();
        if (imagePaths == null) {
            return images;
        }
        for (String imagePath : imagePaths) {
            if (imagePath == null || imagePath.isBlank()) {
                continue;
            }
            Path path = files.requireOwnedFile(Path.of(imagePath), context).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                warnings.add("图片文件不存在：" + imagePath);
                continue;
            }
            path = path.toRealPath();
            String fileName = path.getFileName().toString();
            Path previous = images.putIfAbsent(fileName, path);
            if (previous != null && !previous.equals(path)) {
                throw new IOException("存在同名图片，无法确定引用：" + fileName);
            }
            images.put(imagePath.replace('\\', '/'), path);
            images.put(Path.of(imagePath).normalize().toString().replace('\\', '/'), path);
            images.put(path.toString().replace('\\', '/'), path);
        }
        return images;
    }

    /** 为当前文档注册中文常规字体和粗体，避免在不同 PDF 间共享字体状态。 */
    private FontProvider createFontProvider() throws IOException {
        FontProvider provider = new FontProvider();
        provider.getFontSet().addFont(loadFont(regularFont), PdfEncodings.IDENTITY_H, FONT_FAMILY);
        provider.getFontSet().addFont(loadFont(boldFont), PdfEncodings.IDENTITY_H, FONT_FAMILY);
        return provider;
    }

    /** 通过流加载字体，兼容可执行 JAR 内资源，不依赖服务器绝对路径。 */
    private FontProgram loadFont(String location) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IOException("字体资源未配置，请检查 pdf.fonts 配置");
        }
        Resource resource = location.startsWith("classpath:") || location.startsWith("file:")
                ? new DefaultResourceLoader().getResource(location)
                : new FileSystemResource(location);
        if (!resource.exists()) {
            throw new IOException("字体资源不存在，请检查 pdf.fonts 配置：" + location);
        }
        try (var input = resource.getInputStream()) {
            return FontProgramFactory.createFont(input.readAllBytes());
        }
    }
}
