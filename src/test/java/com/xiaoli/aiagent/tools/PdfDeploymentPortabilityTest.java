package com.xiaoli.aiagent.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.imageio.ImageIO;

/** 实际生成并解析 PDF，验证中文、字体嵌入、相对图片路径与 JAR 内资源加载。 */
class PdfDeploymentPortabilityTest {
    @TempDir Path directory;

    @Test
    void packagedFontsRenderChineseAndEmbedAnImageFromItsRelativeDownloadPath() throws Exception {
        Path relative = Path.of("").toAbsolutePath().relativize(directory);
        Path image = relative.resolve("download/图片.png");
        Files.createDirectories(image.getParent());
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        String imagePath = image.toString().replace('\\', '/');
        var tool =
                new PDFGenerationTool(
                        relative.toString(),
                        PDFGenerationTool.DEFAULT_REGULAR_FONT,
                        PDFGenerationTool.DEFAULT_BOLD_FONT);
        String result =
                tool.generatePDF(
                        "部署报告.pdf",
                        "# 中文标题\n\n**加粗结论**\n\n![图片说明](" + imagePath + ")",
                        List.of(imagePath));
        assertTrue(result.startsWith("PDF generated successfully to: "), result);
        assertFalse(result.contains("图片引用未匹配"), result);
        try (var pdf =
                new PdfDocument(new PdfReader(directory.resolve("pdf/部署报告.pdf").toString()))) {
            String text = extractedText(pdf);
            assertTrue(text.contains("中文标题"), text);
            assertTrue(text.contains("加粗结论"), text);
            assertNotNull(pdf.getPage(1).getResources().getResource(PdfName.XObject));
            var fonts = pdf.getPage(1).getResources().getResource(PdfName.Font);
            assertFalse(fonts.isEmpty());
            for (PdfName name : fonts.keySet()) {
                var font =
                        fonts.getAsDictionary(name)
                                .getAsArray(PdfName.DescendantFonts)
                                .getAsDictionary(0);
                var descriptor = font.getAsDictionary(PdfName.FontDescriptor);
                assertTrue(
                        descriptor.containsKey(PdfName.FontFile3)
                                || descriptor.containsKey(PdfName.FontFile2));
            }
        }
    }

    @Test
    void fontsCanBeLoadedFromAJarWithoutConvertingResourcesToFiles() throws Exception {
        Path jar = directory.resolve("fonts.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            copyFont(output, "fonts/NotoSansSC-Regular.otf", "jar-fonts/regular.otf");
            copyFont(output, "fonts/NotoSansSC-Bold.otf", "jar-fonts/bold.otf");
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            var tool =
                    new PDFGenerationTool(
                            directory.toString(),
                            "classpath:jar-fonts/regular.otf",
                            "classpath:jar-fonts/bold.otf");
            String result = tool.generatePDF("jar-fonts.pdf", "打包后的中文字体");
            assertTrue(result.startsWith("PDF generated successfully"), result);
            try (var pdf =
                    new PdfDocument(
                            new PdfReader(directory.resolve("pdf/jar-fonts.pdf").toString()))) {
                assertTrue(extractedText(pdf).contains("打包后的中文字体"));
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void missingExternalFontReturnsAnActionableConfigurationMessage() {
        var tool =
                new PDFGenerationTool(
                        directory.toString(), "missing/regular.otf", "missing/bold.otf");
        String result = tool.generatePDF("missing-font.pdf", "中文内容");
        assertTrue(result.startsWith("Error generating PDF:"), result);
        assertTrue(result.contains("pdf.fonts"), result);
    }

    /** 将项目字体复制到测试 JAR，避免测试只覆盖解压后的 classpath 文件。 */
    private void copyFont(JarOutputStream output, String resource, String entry) throws Exception {
        output.putNextEntry(new JarEntry(entry));
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            input.transferTo(output);
        }
        output.closeEntry();
    }

    /** CJK 字体可能将相同字形抽取为兼容部首；规范化后核对文字内容，仍独立检查字体实际嵌入。 */
    private String extractedText(PdfDocument pdf) {
        return Normalizer.normalize(
                PdfTextExtractor.getTextFromPage(pdf.getPage(1)), Normalizer.Form.NFKC);
    }
}
