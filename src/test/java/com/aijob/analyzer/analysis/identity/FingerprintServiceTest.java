package com.aijob.analyzer.analysis.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FingerprintServiceTest {

    private final FingerprintService service = new FingerprintService();

    @Test
    void normalizesLineEndingsBeforeHashing() {
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id", "Java Intern", "北京", 4, "3个月", "200/天", "A\r\nB"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id", "Java Intern", "北京", 4, "3个月", "200/天", "A\nB"));
        assertEquals(a, b);
    }

    @Test
    void sameCanonicalJobContentProducesSameSha256() {
        var a = service.jobFingerprint(new JobFingerprintInput("p1", "id1", "Title", "City", 5, "Duration", "Salary", "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p2", "id2", "Title", "City", 5, "Duration", "Salary", "Desc"));
        assertEquals(a, b);
        assertEquals(64, a.value().length());
        assertTrue(a.value().matches("[0-9a-f]+"));
    }

    @Test
    void differentDescriptionProducesDifferentFingerprint() {
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration", "Salary", "Desc A"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration", "Salary", "Desc B"));
        assertNotEquals(a, b);
    }

    @Test
    void cityChangeProducesDifferentFingerprint() {
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City A", 5, "Duration", "Salary", "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City B", 5, "Duration", "Salary", "Desc"));
        assertNotEquals(a, b);
    }

    @Test
    void internshipDaysChangeProducesDifferentFingerprint() {
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration", "Salary", "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 6, "Duration", "Salary", "Desc"));
        assertNotEquals(a, b);
    }

    @Test
    void durationChangeProducesDifferentFingerprint() {
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration A", "Salary", "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration B", "Salary", "Desc"));
        assertNotEquals(a, b);
    }

    @Test
    void salaryChangeProducesDifferentFingerprint() {
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration", "Salary A", "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration", "Salary B", "Desc"));
        assertNotEquals(a, b);
    }

    @Test
    void sourcePlatformChangeOnlyProducesSameFingerprint() {
        var a = service.jobFingerprint(new JobFingerprintInput("p1", "id", "Title", "City", 5, "Duration", "Salary", "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p2", "id", "Title", "City", 5, "Duration", "Salary", "Desc"));
        assertEquals(a, b);
    }

    @Test
    void sourceJobIdChangeOnlyProducesSameFingerprint() {
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id1", "Title", "City", 5, "Duration", "Salary", "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id2", "Title", "City", 5, "Duration", "Salary", "Desc"));
        assertEquals(a, b);
    }

    @Test
    void rejectsNullOrBlankTitleAndDescription() {
        assertThrows(IllegalArgumentException.class, () -> service.jobFingerprint(new JobFingerprintInput("p", "id", null, "City", 5, "Duration", "Salary", "Desc")));
        assertThrows(IllegalArgumentException.class, () -> service.jobFingerprint(new JobFingerprintInput("p", "id", "  ", "City", 5, "Duration", "Salary", "Desc")));
        assertThrows(IllegalArgumentException.class, () -> service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration", "Salary", null)));
        assertThrows(IllegalArgumentException.class, () -> service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City", 5, "Duration", "Salary", "  ")));
    }

    @Test
    void normalizesHorizontalWhitespaceButPreservesNewlinesInScalars() {
        // Scalar "City A\tB" should become "City A B"
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City A B", 5, "Duration", "Salary", "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City A\tB", 5, "Duration", "Salary", "Desc"));
        assertEquals(a, b);

        // But "City A\nB" should NOT be same as "City A B"
        var c = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "City A\nB", 5, "Duration", "Salary", "Desc"));
        assertNotEquals(a, c);
    }

    @Test
    void nullScalarsCanonicalizeToEmptyString() {
        var a = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", null, 5, null, null, "Desc"));
        var b = service.jobFingerprint(new JobFingerprintInput("p", "id", "Title", "", 5, "", "", "Desc"));
        assertEquals(a, b);
    }

    @Test
    void matchesKnownCanonicalHash() {
        // Payload calculation:
        // title=Java Intern\n
        // city=北京\n
        // internshipDays=4\n
        // duration=3个月\n
        // salary=200/天\n
        // description=Line 1\nLine 2
        var input = new JobFingerprintInput("p", "id", "Java Intern", "北京", 4, "3个月", "200/天", "Line 1\nLine 2");
        var fingerprint = service.jobFingerprint(input);

        assertEquals("78f89a9703cd0c44f7f023d7f57edd9c06faf5f907e7267ea50a4f1fb187c6cd", fingerprint.value());
    }
}
