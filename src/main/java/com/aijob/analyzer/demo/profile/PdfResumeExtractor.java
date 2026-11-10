package com.aijob.analyzer.demo.profile;

import com.aijob.analyzer.demo.DemoException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class PdfResumeExtractor {
    public record Extracted(String text, int pages) {}
    public Extracted extract(byte[] bytes) {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw new DemoException(422, "PDF_ENCRYPTED", "请上传解密后的 PDF 或使用文本补救入口");
            if (document.getNumberOfPages() > 100) throw new DemoException(422, "PDF_TOO_MANY_PAGES", "PDF 最多允许 100 页");
            String text = new PDFTextStripper().getText(document).strip();
            if (text.isBlank()) throw new DemoException(422, "PDF_NO_TEXT", "未提取到文本，可能为空白或扫描件，请粘贴可核查的简历文本");
            if (text.length() > 100000) throw new DemoException(422, "RESUME_TEXT_TOO_LONG", "简历文本超过 100000 字符");
            return new Extracted(text, document.getNumberOfPages());
        } catch (InvalidPasswordException e) {
            throw new DemoException(422, "PDF_ENCRYPTED", "PDF 已加密，请解密后上传或使用文本补救入口");
        } catch (IOException | IllegalArgumentException e) {
            throw new DemoException(422, "PDF_CORRUPT", "PDF 无法解析，请检查文件或使用文本补救入口");
        }
    }
}
