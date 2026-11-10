package com.aijob.analyzer.demo;

import com.aijob.analyzer.rules.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RulesEngineTest {
    private com.aijob.analyzer.analysis.domain.JobContext requirements(String city, String location, Integer days, String duration, Integer months, String jd) {
        return new com.aijob.analyzer.analysis.domain.JobContext("TEST", "review", "Java 后端", city, days,
                duration, "200元/天", "Java " + jd, new com.aijob.analyzer.analysis.identity.JobFingerprint("REVIEW"),
                new com.aijob.analyzer.analysis.domain.JobDetails("TEST", location, "本科", "不限", java.util.List.of("Java"), "JAVA_BACKEND", months, null));
    }
    @Test void conflictingAndUnrecognizedDetailedLocationsRemainUnknown() throws Exception {
        for (String location : java.util.List.of("北京", "北京市海淀区中关村", "科技园一号楼", "上海路1号")) {
            var result = new RulesEngine().evaluate(requirements("上海", location, 4, "3个月", 3, ""), config());
            assertEquals(RuleModels.Verdict.UNKNOWN, verdict(result, "city"), location);
            assertEquals(com.aijob.analyzer.workflow.domain.RuleSignal.CONTINUE, result.signal());
        }
    }
    @Test void allExplicitCityMentionsAreCompared() throws Exception {
        var engine = new RulesEngine();
        assertEquals(RuleModels.Verdict.UNKNOWN, verdict(engine.evaluate(requirements("上海", "上海浦东", 4, "3个月", 3,
                "工作地点：上海。城市：北京"), config()), "city"));
        assertEquals(RuleModels.Verdict.SATISFIED, verdict(engine.evaluate(requirements("上海", "上海市浦东新区", 4, "3个月", 3,
                "工作地点：上海。城市：上海"), config()), "city"));
    }
    @Test void allAttendanceRequirementsMustAgree() throws Exception {
        for (Integer structured : java.util.Arrays.asList(null, 4)) {
            var result = new RulesEngine().evaluate(requirements("上海", "上海", structured, "3个月", 3,
                    "每周至少4天；每周至少6天"), config());
            assertEquals(RuleModels.Verdict.UNKNOWN, verdict(result, "attendance"));
            assertEquals(com.aijob.analyzer.workflow.domain.RuleSignal.CONTINUE, result.signal());
        }
        var same = new RulesEngine().evaluate(requirements("上海", "上海", 4, "3个月", 3,
                "每周至少4天；每周4天"), config());
        assertEquals(RuleModels.Verdict.SATISFIED, verdict(same, "attendance"));
    }
    @Test void allPeriodRequirementsMustAgree() throws Exception {
        for (Integer structured : java.util.Arrays.asList(null, 3)) {
            var result = new RulesEngine().evaluate(requirements("上海", "上海", 4, "", structured,
                    "至少实习3个月；周期9个月"), config());
            assertEquals(RuleModels.Verdict.UNKNOWN, verdict(result, "duration"));
            assertEquals(com.aijob.analyzer.workflow.domain.RuleSignal.CONTINUE, result.signal());
        }
        assertEquals(RuleModels.Verdict.SATISFIED, verdict(new RulesEngine().evaluate(requirements("上海", "上海", 4,
                "3个月", 3, "至少实习3个月；周期3个月"), config()), "duration"));
        assertEquals(RuleModels.Verdict.UNKNOWN, verdict(new RulesEngine().evaluate(requirements("上海", "上海", 4,
                "3个月", 9, "至少实习3个月；周期3个月"), config()), "duration"));
    }
    private RuleModels.Config config() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        return json.treeToValue(json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/demo/fixtures/rules-v1.json"))).path("config"),RuleModels.Config.class);
    }
    private com.aijob.analyzer.analysis.domain.JobContext job(String title,String salary,String duration,Integer months) {
        return new com.aijob.analyzer.analysis.domain.JobContext("TEST","unit",title,"上海",4,duration,salary,"Java backend",
                new com.aijob.analyzer.analysis.identity.JobFingerprint("TEST"),new com.aijob.analyzer.analysis.domain.JobDetails("TEST COMPANY","上海","本科","不限",java.util.List.of("Java"),"",months,null));
    }
    private RuleModels.Verdict verdict(com.aijob.analyzer.workflow.domain.RuleResult result,String field) {
        return result.items().stream().filter(i->i.field().equals(field)).findFirst().orElseThrow().verdict();
    }
    @Test void candidateFiveDaysMeetsFourDayRequirement() {
        var result = new RulesEngine().compareCapacity("attendance", 5, 4, "DAYS_PER_WEEK", RuleModels.Strength.HARD, "structured");
        assertEquals(RuleModels.Verdict.SATISFIED, result.verdict());
    }
    @Test void missingRequirementRemainsUnknown() {
        assertEquals(RuleModels.Verdict.UNKNOWN, new RulesEngine().compareCapacity("attendance", 5, null, "DAYS_PER_WEEK", RuleModels.Strength.HARD,"missing").verdict());
    }
    @Test void javascriptBackendDoesNotBecomeJavaBackend() throws Exception {
        assertEquals(RuleModels.Verdict.UNKNOWN,verdict(new RulesEngine().evaluate(job("JavaScript backend","200元/天","3个月",3),config()),"direction"));
    }
    @Test void salaryUnitsAndOverlapRemainConservative() throws Exception {
        var engine=new RulesEngine();var config=config();
        assertEquals(RuleModels.Verdict.UNKNOWN,verdict(engine.evaluate(job("Java 后端","6000元/月","3个月",3),config),"salary"));
        assertEquals(RuleModels.Verdict.UNSATISFIED,verdict(engine.evaluate(job("Java 后端","50元/天","3个月",3),config),"salary"));
        assertEquals(RuleModels.Verdict.UNKNOWN,verdict(engine.evaluate(job("Java 后端","100-200元/天","3个月",3),config),"salary"));
    }
    @Test void conflictingPeriodRemainsUnknown() throws Exception {
        assertEquals(RuleModels.Verdict.UNKNOWN,verdict(new RulesEngine().evaluate(job("Java 后端","200元/天","6个月",3),config()),"duration"));
    }
    @Test void directProductionPortUsesSavedConfiguration() throws Exception {
        var configs=org.mockito.Mockito.mock(RuleConfigService.class);
        org.mockito.Mockito.when(configs.current()).thenReturn(new RuleModels.Snapshot(1,RuleModels.ALGORITHM,"TEST",config()));
        assertEquals(com.aijob.analyzer.workflow.domain.RuleSignal.HARD_REJECT,new RulesEngine(configs).check(job("Java 后端","50元/天","3个月",3),null).signal());
    }
    @Test void unrecognizedCityIsNotAHardRejection() throws Exception {
        var original=job("Java 后端","200元/天","3个月",3);
        for(String city:java.util.List.of("Guangzhou","远程","全国","不限")) {
            var unknown=new com.aijob.analyzer.analysis.domain.JobContext(original.sourcePlatform(),original.sourceJobId(),original.title(),city,original.internshipDays(),original.duration(),original.salary(),original.description(),original.jobFingerprint(),original.details());
            assertEquals(RuleModels.Verdict.UNKNOWN,verdict(new RulesEngine().evaluate(unknown,config()),"city"));
        }
    }
}
