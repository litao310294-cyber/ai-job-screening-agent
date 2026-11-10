package com.aijob.analyzer.demo;

import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;

/** Generated only in target; contains no real candidate information. */
public final class SyntheticResumeFixture {
    public static byte[] bytes() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(40, 750);
                for (int i = 0; i < 40; i++) {
                    stream.showText("SYNTHETIC TEST ONLY: Java Spring Boot APIs, unit tests and sample project " + i);
                    stream.newLineAtOffset(0, -16);
                }
                stream.endText();
            }
            document.save(output); return output.toByteArray();
        }
    }
    public static byte[] suppliedOrSynthetic() throws Exception {
        String supplied = System.getProperty("demoResumePdf");
        return supplied == null ? bytes() : Files.readAllBytes(Path.of(supplied));
    }
    public static void main(String[] args) throws Exception {
        Path path = Path.of("target/synthetic-resume.pdf");
        Files.createDirectories(path.getParent()); Files.write(path, bytes());
        System.out.println("Generated target/synthetic-resume.pdf (synthetic test data)");
    }
}
