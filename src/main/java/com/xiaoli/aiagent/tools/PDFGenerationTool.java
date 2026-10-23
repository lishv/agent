package com.xiaoli.aiagent.tools;

import com.xiaoli.aiagent.constant.FileConstant;
import com.xiaoli.aiagent.service.GeneratedFileService;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** PDF 工具入口：校验输入、生成并核验文档，然后返回可下载的文件。 */
public class PDFGenerationTool {

    /** 随应用打包的中文常规字体，不依赖服务器安装字体。 */
    public static final String DEFAULT_REGULAR_FONT = "classpath:fonts/NotoSansSC-Regular.otf";

    /** 随应用打包的中文粗体，可通过 pdf.fonts.bold 覆盖。 */
    public static final String DEFAULT_BOLD_FONT = "classpath:fonts/NotoSansSC-Bold.otf";

    private final ToolFileStorage files;
    private final String regularFont;
    private final String boldFont;

    /** 独立调用时使用相对目录和打包字体。 */
    public PDFGenerationTool() {
        this(FileConstant.FILE_SAVE_DIR, DEFAULT_REGULAR_FONT, DEFAULT_BOLD_FONT);
    }

    /** 指定输出目录及字体；字体支持 classpath、文件 URI 和本地相对路径。 */
    public PDFGenerationTool(String directory, String regularFont, String boldFont) {
        this(directory, regularFont, boldFont, null);
    }

    /** 应用内复用统一文件服务，保持用户隔离、完整发布和鉴权下载。 */
    public PDFGenerationTool(
            String directory,
            String regularFont,
            String boldFont,
            GeneratedFileService generatedFiles) {
        files = new ToolFileStorage(directory, "pdf", generatedFiles);
        this.regularFont = regularFont;
        this.boldFont = boldFont;
    }

    /** 将 Markdown 生成 PDF，成功时返回下载链接、页数、文件大小和图片引用提示。 */
    @Tool(
            description = """
                    Generate a PDF from Markdown, including headings, lists, tables, blockquotes and code blocks.
                    Download images first and pass their exact returned local paths in imagePaths.
                    Reference only relevant images in Markdown; unreferenced images are not inserted.
                    The PDF is validated before publishing. The result includes page count and file size.
                    Reuse the exact returned Markdown download link. Never invent sandbox: URLs.
                    """,
            returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "Name of the PDF file to save") String fileName,
            @ToolParam(description = """
                    Valid Markdown content. Place each image reference on a separate line:
                    ![caption](filename.jpg). The filename must match a downloaded file in imagePaths.
                    Use fenced code blocks for code and Markdown tables for tabular data.
                    """) String content,
            @ToolParam(description = """
                    Relative or absolute local paths of successfully downloaded images.
                    Reference these files by filename in Markdown. Unreferenced images are not inserted.
                    """, required = false) List<String> imagePaths,
            ToolContext context) {
        if (fileName == null || fileName.isBlank()) {
            return "Error generating PDF: fileName 不能为空";
        }
        if (content == null || content.isBlank()) {
            return "Error generating PDF: content 不能为空";
        }
        try {
            // 每次调用独立保存页数和图片提示，并发任务之间不共享渲染状态。
            PdfDocumentRenderer renderer =
                    new PdfDocumentRenderer(regularFont, boldFont, files, context);
            Path file = files.write(fileName, context, path -> renderer.write(path, content, imagePaths));
            String result = "PDF generated successfully to: " + files.describe(file, context)
                    + "\n页数：" + renderer.pageCount() + "；文件大小：" + Files.size(file) + " 字节";
            if (!renderer.warnings().isEmpty()) {
                result += "\n注意：" + String.join("；", renderer.warnings());
            }
            return result;
        } catch (Exception e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }

    /** 保留带图片列表的独立调用方式。 */
    public String generatePDF(String fileName, String content, List<String> imagePaths) {
        return generatePDF(fileName, content, imagePaths, null);
    }

    /** 生成不含图片的 Markdown 文档。 */
    public String generatePDF(String fileName, String content) {
        return generatePDF(fileName, content, List.of());
    }
}
