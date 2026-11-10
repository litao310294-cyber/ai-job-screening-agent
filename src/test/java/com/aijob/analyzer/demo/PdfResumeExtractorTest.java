package com.aijob.analyzer.demo;

import com.aijob.analyzer.demo.profile.PdfResumeExtractor;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PdfResumeExtractorTest {
    static byte[] pdf(String text, boolean encrypted) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(30, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            if (encrypted) document.protect(new StandardProtectionPolicy("owner", "secret", new AccessPermission()));
            document.save(output);
            return output.toByteArray();
        }
    }
    @Test void textAndPdfFailures() throws Exception {
        var extractor = new PdfResumeExtractor();
        assertTrue(extractor.extract(pdf("Java backend resume", false)).text().contains("Java backend"));
        assertEquals("PDF_NO_TEXT", assertThrows(DemoException.class, () -> extractor.extract(pdf(null, false))).code());
        assertEquals("PDF_ENCRYPTED", assertThrows(DemoException.class, () -> extractor.extract(pdf("Java", true))).code());
        assertEquals("PDF_CORRUPT", assertThrows(DemoException.class, () -> extractor.extract("%PDF broken".getBytes())).code());
    }
    @Test void specifiedPdfExtractsDirectly() throws Exception {
        var result = new PdfResumeExtractor().extract(SyntheticResumeFixture.suppliedOrSynthetic());
        assertTrue(result.text().length() > 1000);
    }
}
