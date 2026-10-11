package com.xiaoli.aiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.font.FontProvider;
import com.xiaoli.aiagent.constant.FileConstant;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 生成工具。
 * 支持 Markdown 排版、按正文位置插入本地图片和中文字体嵌入。
 */
public class PDFGenerationTool {

    /**
     * 当前 Windows 环境的中文字体。
     */
    private static final String REGULAR_FONT =
            "C:/Windows/Fonts/msyh.ttc,0";

    private static final String BOLD_FONT =
            "C:/Windows/Fonts/msyhbd.ttc,0";

    /**
     * PDF 文档样式。
     */
    private static final String CSS = """
            @page {
                size: A4;
                margin: 42pt;
            }

            body {
                font-family: PdfChinese;
                font-size: 11pt;
                line-height: 1.6;
                color: #222;
            }

            h1 { font-size: 22pt; }
            h2 { font-size: 17pt; }
            h3 { font-size: 14pt; }

            h1, h2, h3, h4, h5, h6 {
                margin: 14pt 0 8pt;
                page-break-after: avoid;
            }

            p {
                margin: 0 0 8pt;
            }

            ul, ol {
                margin: 6pt 0 10pt;
                padding-left: 22pt;
            }

            li {
                margin-bottom: 4pt;
            }

            img {
                display: block;
                max-width: 100%;
                max-height: 220pt;
                width: auto;
                height: auto;
                margin: 10pt auto;
            }

            .caption {
                display: block;
                text-align: center;
                font-size: 9pt;
                color: #666;
                margin-bottom: 10pt;
            }

            .image-warning {
                color: #888;
                font-size: 9pt;
            }

            blockquote {
                margin: 8pt 0;
                padding-left: 12pt;
                border-left: 3pt solid #bbb;
                color: #555;
            }

            table {
                width: 100%;
                border-collapse: collapse;
                margin: 10pt 0;
            }

            th, td {
                border: 0.5pt solid #ccc;
                padding: 5pt;
                font-size: 9pt;
            }

            th {
                background-color: #f2f2f2;
            }

            tr {
                page-break-inside: avoid;
            }
            """;

    /**
     * 生成 PDF 文件。
     *
     * @param fileName PDF 文件名
     * @param content Markdown 内容
     * @param imagePaths 已下载的图片路径列表
     * @return 生成结果及图片引用提示
     */
    @Tool(
            description = """
                    Generate a PDF from valid Markdown content.
                    Supports headings, bold text, lists, blockquotes and tables.
                    Images are inserted at their Markdown reference positions.
                    Download images first and pass their absolute local paths.
                    """,
            returnDirect = false
    )
    /**
     * 生成 PDF 文件。
     */
    public String generatePDF(
            @ToolParam(description = "Name of the PDF file to save")
            String fileName,
            @ToolParam(description = """
                    Valid Markdown content.
                    Place each image reference on a separate line using:
                    ![caption](filename.jpg)
                    The filename must match a downloaded file in imagePaths.
                    """)
            String content,
            @ToolParam(
                    description = """
                            Absolute local paths of successfully downloaded images.
                            Refer to these files by filename in the Markdown content.
                            Unreferenced images will not be inserted.
                            """,
                    required = false
            )
            List<String> imagePaths) {

        if (fileName == null || fileName.isBlank()) {
            return "Error generating PDF: fileName 不能为空";
        }

        if (content == null || content.isBlank()) {
            return "Error generating PDF: content 不能为空";
        }

        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;// 生成 PDF 文件路径

        try {
            FileUtil.mkdir(fileDir);

            List<String> warnings = new ArrayList<>();
            String html = buildHtml(content, imagePaths, warnings);

            ConverterProperties properties = new ConverterProperties();
            properties.setFontProvider(createFontProvider());

            try (PdfWriter writer = new PdfWriter(filePath)) {
                HtmlConverter.convertToPdf(html, writer, properties);
            }

            String result = "PDF generated successfully to: " + filePath;

            if (!warnings.isEmpty()) {
                result += "\n注意：" + String.join("；", warnings);
            }

            return result;
        } catch (IOException | RuntimeException e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }

    /**
     * 生成不带图片的 PDF。
     */
    public String generatePDF(String fileName, String content) {
        return generatePDF(fileName, content, List.of());
    }

    /**
     * 为当前 PDF 注册中文常规字体和粗体字体。
     */
    private FontProvider createFontProvider() throws IOException {
        FontProvider provider = new FontProvider();

        provider.getFontSet().addFont(
                FontProgramFactory.createFont(REGULAR_FONT),
                PdfEncodings.IDENTITY_H,
                "PdfChinese"
        );

        provider.getFontSet().addFont(
                FontProgramFactory.createFont(BOLD_FONT),
                PdfEncodings.IDENTITY_H,
                "PdfChinese"
        );

        return provider;
    }

    /**
     * Markdown 转 HTML，并处理本地图片引用。
     */
    private String buildHtml(
            String content,
            List<String> imagePaths,
            List<String> warnings) throws IOException {

        List<Extension> extensions = List.of(TablesExtension.create());

        Parser parser = Parser.builder()
                .extensions(extensions)
                .build();

        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .build();

        org.jsoup.nodes.Document html = Jsoup.parse(
                renderer.render(parser.parse(content))
        );

        Map<String, Path> images = buildImageIndex(imagePaths, warnings);

        for (Element image : html.select("img")) {
            String source = image.attr("src").replace('\\', '/');
            Path path = images.get(source);

            if (path == null) {
                warnings.add("图片引用未匹配下载文件：" + source);

                image.before(new Element("span")
                        .addClass("image-warning")
                        .text("【图片未提供：" + image.attr("alt") + "】"));

                image.remove();
                continue;
            }

            // 转换为本地文件 URI，支持空格和中文路径
            image.attr("src", path.toUri().toASCIIString());

            String caption = image.attr("alt");
            if (!caption.isBlank()) {
                image.after(new Element("span")
                        .addClass("caption")
                        .text(caption));
            }
        }

        html.head().appendElement("meta")
                .attr("charset", "UTF-8");

        html.head().appendElement("style")
                .text(CSS);

        return html.outerHtml();
    }

    /**
     * 建立文件名、绝对路径与本地图片的对应关系。
     */
    private Map<String, Path> buildImageIndex(
            List<String> imagePaths,
            List<String> warnings) throws IOException {

        Map<String, Path> images = new HashMap<>();

        if (imagePaths == null) {
            return images;
        }

        for (String imagePath : imagePaths) {
            if (imagePath == null || imagePath.isBlank()) {
                continue;
            }

            Path path = Path.of(imagePath)
                    .toAbsolutePath()
                    .normalize();

            if (!Files.isRegularFile(path)) {
                warnings.add("图片文件不存在：" + imagePath);
                continue;
            }

            path = path.toRealPath();

            String fileName = path.getFileName().toString();
            Path previous = images.putIfAbsent(fileName, path);

            // 同名文件指向不同路径时，正文中的文件名无法唯一匹配
            if (previous != null && !previous.equals(path)) {
                throw new IOException(
                        "存在同名图片，无法确定引用：" + fileName
                );
            }
            images.put(
                    path.toString().replace('\\', '/'),
                    path
            );
        }

        return images;
    }
}