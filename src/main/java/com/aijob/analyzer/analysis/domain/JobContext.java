package com.aijob.analyzer.analysis.domain;

import com.aijob.analyzer.analysis.identity.JobFingerprint;

public record JobContext(
        String sourcePlatform,
        String sourceJobId,
        String title,
        String city,
        Integer internshipDays,
        String duration,
        String salary,
        String description,
        JobFingerprint jobFingerprint, JobDetails details) {
    public JobContext {
        details = details == null ? JobDetails.empty() : details;
    }
    public JobContext(String platform, String sourceId, String title, String city, Integer days, String duration,
                      String salary, String description, JobFingerprint fingerprint) {
        this(platform,sourceId,title,city,days,duration,salary,description,fingerprint,JobDetails.empty());
    }
    public String analysisText() {
        return "Title: " + title + "\nCity: " + city + "\nCompany: " + details.company()
                + "\nLocation: " + details.location() + "\nEducation: " + details.education()
                + "\nExperience: " + details.experience() + "\nSkills: " + details.skills()
                + "\nDirection: " + details.direction() + "\nDaysPerWeek: " + internshipDays
                + "\nMonths: " + details.internshipMonths() + "\nDuration: " + duration
                + "\nSalary: " + salary + "\nSalaryStructured: " + details.compensation() + "\nOriginal JD:\n" + description;
    }
}
