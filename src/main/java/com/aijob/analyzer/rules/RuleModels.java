package com.aijob.analyzer.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class RuleModels {
    private RuleModels() {}
    public static final String ALGORITHM = "rules-2b-v2";
    public enum Strength { DISABLED, SOFT, HARD }
    public enum Verdict { SATISFIED, UNSATISFIED, UNKNOWN }
    public record Candidate(Integer availableDaysPerWeek, Integer availableMonths, LocalDate availableFrom) {}
    public record Criteria(List<String> cities, List<String> directions, BigDecimal salaryMin, BigDecimal salaryMax,
                           String salaryUnit, List<String> excludedKeywords) {
        public Criteria {
            cities = cities == null ? List.of() : List.copyOf(cities);
            directions = directions == null ? List.of() : List.copyOf(directions);
            excludedKeywords = excludedKeywords == null ? List.of() : List.copyOf(excludedKeywords);
        }
    }
    public record Modes(Strength city, Strength direction, Strength attendance, Strength duration, Strength salary, Strength excludedKeywords) {}
    public record Config(Candidate candidate, Criteria criteria, Modes modes, List<String> softNotes) {
        public Config { softNotes = softNotes == null ? List.of() : List.copyOf(softNotes); }
    }
    public record Save(long expectedVersion, Config config) {}
    public record Snapshot(long version, String algorithmVersion, String fingerprint, Config config) {
        public String identity() { return algorithmVersion + ":" + version + ":" + fingerprint; }
    }
    public record Item(String field, Verdict verdict, Strength strength, String candidateValue, String jobValue,
                       String unit, String candidateSource, String jobSource, String reason) {}
    public static Config empty() {
        return new Config(new Candidate(null,null,null), new Criteria(null,null,null,null,null,null),
                new Modes(Strength.DISABLED,Strength.DISABLED,Strength.DISABLED,Strength.DISABLED,Strength.DISABLED,Strength.DISABLED), List.of());
    }
}
