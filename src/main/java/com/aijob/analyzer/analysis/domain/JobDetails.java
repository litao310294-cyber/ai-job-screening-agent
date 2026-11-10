package com.aijob.analyzer.analysis.domain;

import java.math.BigDecimal;
import java.util.List;

public record JobDetails(String company, String location, String education, String experience, List<String> skills,
                         String direction, Integer internshipMonths, Salary compensation) {
    public record Salary(BigDecimal min, BigDecimal max, String unit) {}
    public JobDetails { skills = skills == null ? List.of() : List.copyOf(skills); }
    public static JobDetails empty() { return new JobDetails("","","","",List.of(),"",null,null); }
}
