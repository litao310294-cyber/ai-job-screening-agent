package com.aijob.analyzer.rules;

import com.aijob.analyzer.analysis.domain.*;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.workflow.domain.*;
import com.aijob.analyzer.workflow.port.RuleCheckService;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static com.aijob.analyzer.rules.RuleModels.*;

public class RulesEngine implements RuleCheckService {
    private final RuleConfigService configs;
    public RulesEngine() { this(null); }
    public RulesEngine(RuleConfigService configs) { this.configs=configs; }
    @Override public RuleResult check(JobContext job, CandidateProfile profile) { return evaluate(job,configs==null?empty():configs.current().config()); }
    @Override public RuleResult check(JobContext job, CandidateProfile profile, Snapshot snapshot) { return evaluate(job,snapshot.config()); }
    public Item compareCapacity(String field,Integer candidate,Integer required,String unit,Strength strength,String source) {
        Verdict verdict=candidate==null || required==null?Verdict.UNKNOWN:candidate>=required?Verdict.SATISFIED:Verdict.UNSATISFIED;
        return item(field,verdict,strength,candidate,required,unit,source,verdict==Verdict.UNKNOWN?"候选人条件或岗位要求缺失/冲突":"候选人可提供值必须不小于岗位最低要求");
    }
    public RuleResult evaluate(JobContext job, Config config) {
        var c=config.candidate(); var criteria=config.criteria(); var m=config.modes();
        List<Item> items=new ArrayList<>();
        items.add(compareCities(job, criteria, m.city()));
        String direction=direction(job);
        items.add(item("direction",direction.isBlank() || criteria.directions().isEmpty()?Verdict.UNKNOWN:
                criteria.directions().contains(direction)?Verdict.SATISFIED:Verdict.UNSATISFIED,m.direction(),criteria.directions(),direction,"DIRECTION",
                "岗位结构化方向或标题分类","只使用明确结构化方向或标题，技能关键词不足以确定岗位方向"));
        var dayValues = new ArrayList<Integer>();
        if (job.internshipDays() != null) dayValues.add(job.internshipDays());
        matches(job.description(), "每周(?:至少)?\\s*([1-7])\\s*天").stream().map(this::integer).forEach(dayValues::add);
        Integer days = agreedRequirement(dayValues);
        items.add(compareCapacity("attendance",c.availableDaysPerWeek(),days,"DAYS_PER_WEEK",m.attendance(),"internshipDays / 全部 JD 每周最低天数=" + dayValues));
        Integer rawMonths=integer(match(job.duration(),"^(?:至少)?\\s*(\\d+)\\s*(?:个)?月(?:以上)?$"));
        var monthValues=new ArrayList<Integer>();
        for(var value:Arrays.asList(job.details().internshipMonths(),rawMonths)) if(value!=null) monthValues.add(value);
        matches(job.description(), "(?:至少实习|实习至少|至少|周期)\\s*(\\d+)\\s*(?:个)?月").stream().map(this::integer).forEach(monthValues::add);
        Integer months = agreedRequirement(monthValues);
        if(job.duration()!=null && !job.duration().isBlank() && rawMonths==null) months=null;
        items.add(compareCapacity("duration",c.availableMonths(),months,"MONTHS",m.duration(),"internshipMonths / duration / 全部 JD 明确月数=" + monthValues));
        items.add(salary(job,criteria,m.salary()));
        String values=String.join("\n",String.valueOf(job.title()),String.valueOf(job.description()),String.valueOf(job.details().company()),
                String.valueOf(job.details().location()),String.valueOf(job.details().education()),String.valueOf(job.details().experience()),String.join(" ",job.details().skills()));
        var hits=criteria.excludedKeywords().stream().filter(k->values.toLowerCase(Locale.ROOT).contains(k.toLowerCase(Locale.ROOT))).toList();
        items.add(item("excludedKeywords",criteria.excludedKeywords().isEmpty()?Verdict.UNKNOWN:hits.isEmpty()?Verdict.SATISFIED:Verdict.UNSATISFIED,
                m.excludedKeywords(),criteria.excludedKeywords(),hits,"LITERAL_TEXT","岗位原始字段","按明确排除词字面匹配"));
        items.add(item("availableFrom",Verdict.UNKNOWN,Strength.DISABLED,c.availableFrom(),null,"DATE","未支持岗位日期比较","日期仅保存展示，需人工确认"));
        boolean reject=items.stream().anyMatch(i->i.strength()==Strength.HARD && i.verdict()==Verdict.UNSATISFIED);
        List<String> reasons=new ArrayList<>(items.stream().filter(i->i.strength()!=Strength.DISABLED).map(i->i.field()+"="+i.verdict()+"; "+i.reason()).toList());
        config.softNotes().forEach(note->reasons.add("SOFT_PREFERENCE_NOTE (user data): "+note));
        return new RuleResult(reject?RuleSignal.HARD_REJECT:RuleSignal.CONTINUE,reasons,items);
    }
    private Item salary(JobContext job,Criteria criteria,Strength mode) {
        JobDetails.Salary raw=parseSalary(job.salary());
        JobDetails.Salary salary=job.details().compensation()==null?raw:job.details().compensation();
        boolean conflict=raw!=null && job.details().compensation()!=null && (!valid(salary) || !raw.unit().equals(salary.unit())
                || raw.min().compareTo(salary.min())!=0 || raw.max().compareTo(salary.max())!=0);
        if(job.details().compensation()!=null && job.salary()!=null && (job.salary().contains("面议") || job.salary().toLowerCase(Locale.ROOT).contains("negotiable"))) conflict=true;
        Verdict verdict=Verdict.UNKNOWN;
        String reason="薪资缺失、面议、单位缺失或不一致，不进行日/月换算";
        if(!conflict && valid(salary) && criteria.salaryUnit()!=null && criteria.salaryUnit().equals(salary.unit()) && (criteria.salaryMin()!=null || criteria.salaryMax()!=null)) {
            boolean below=criteria.salaryMin()!=null && salary.max().compareTo(criteria.salaryMin())<0;
            boolean above=criteria.salaryMax()!=null && salary.min().compareTo(criteria.salaryMax())>0;
            boolean withinMin=criteria.salaryMin()==null || salary.min().compareTo(criteria.salaryMin())>=0;
            boolean withinMax=criteria.salaryMax()==null || salary.max().compareTo(criteria.salaryMax())<=0;
            verdict=below||above?Verdict.UNSATISFIED:withinMin&&withinMax?Verdict.SATISFIED:Verdict.UNKNOWN;
            reason="同单位区间完全包含为满足，完全不相交为不满足，部分重叠待确认";
        }
        if(conflict) reason="结构化薪资与原文薪资冲突";
        return item("salary",verdict,mode,criteria.salaryMin()+".."+criteria.salaryMax(),salary,"CNY/"+criteria.salaryUnit(),"compensation / salary 原文",reason);
    }
    private boolean valid(JobDetails.Salary salary) {
        return salary!=null && salary.min()!=null && salary.max()!=null && salary.min().signum()>=0 && salary.min().compareTo(salary.max())<=0 && Set.of("DAY","MONTH").contains(String.valueOf(salary.unit()));
    }
    private JobDetails.Salary parseSalary(String value) {
        if(value==null) return null;
        Matcher matcher=Pattern.compile("(?i)^\\s*(\\d+(?:\\.\\d+)?)(?:\\s*[-~至]\\s*(\\d+(?:\\.\\d+)?))?\\s*(K)?(?:元)?\\s*/\\s*(天|日|月)\\s*$").matcher(value);
        if(!matcher.matches()) return null;
        BigDecimal scale=matcher.group(3)==null?BigDecimal.ONE:BigDecimal.valueOf(1000);
        var min=new BigDecimal(matcher.group(1)).multiply(scale);
        var max=matcher.group(2)==null?min:new BigDecimal(matcher.group(2)).multiply(scale);
        return new JobDetails.Salary(min,max,matcher.group(4).equals("月")?"MONTH":"DAY");
    }
    private static String city(String value) {
        if(value==null) return "";
        if(Set.of("远程","全国","不限","多地","异地","remote","anywhere").contains(value.strip().toLowerCase(Locale.ROOT))) return "";
        String normalized=switch(value.strip().toLowerCase(Locale.ROOT)) { case "shanghai","上海市"->"上海";case "beijing","北京市"->"北京";case "tianjin","天津市"->"天津";default->value.strip().replaceFirst("市$",""); };
        return normalized.matches("[\\p{IsHan}]{2,6}") && !normalized.matches(".*[省区县].*")?normalized:"";
    }
    private String direction(JobContext job) {
        String explicit=job.details().direction();
        String title=job.title().toLowerCase(Locale.ROOT);
        boolean java=Pattern.compile("(?<![a-z0-9])java(?![a-z0-9])").matcher(title).find() && (title.contains("后端") || title.contains("backend"));
        boolean ai=Pattern.compile("(?<![a-z0-9])ai\\s*(?:应用|application)").matcher(title).find();
        String inferred=java==ai?"":java?"JAVA_BACKEND":"AI_APPLICATION";
        if(explicit==null || explicit.isBlank()) return inferred;
        if(!Set.of("JAVA_BACKEND","AI_APPLICATION","OTHER").contains(explicit)) return "";
        return !inferred.isBlank() && !explicit.equals(inferred)?"":explicit;
    }
    private Item compareCities(JobContext job, Criteria criteria, Strength mode) {
        var sources = new LinkedHashMap<String, String>();
        sources.put("city", job.city());
        if (job.details().location() != null && !job.details().location().isBlank()) {
            sources.put("location", job.details().location());
        }
        var mentioned = matches(job.description(), "(?:工作地点|城市)[：:]\\s*([^\\s，。；,;]+)");
        for (int i = 0; i < mentioned.size(); i++) sources.put("JD地点" + (i + 1), mentioned.get(i));
        var cities = new ArrayList<String>();
        sources.forEach((source, value) -> cities.add(source.equals("city") ? city(value) : addressCity(value)));
        var accepted = criteria.cities().stream().map(RulesEngine::city).toList();
        Verdict verdict = Verdict.UNKNOWN;
        String reason = "城市缺失、地点无法可靠识别或各来源不一致，待确认";
        if (!cities.contains("") && cities.stream().distinct().count() == 1 && !accepted.isEmpty()) {
            if (accepted.contains(cities.getFirst())) verdict = Verdict.SATISFIED;
            else if (!accepted.contains("")) verdict = Verdict.UNSATISFIED;
            reason = "全部明确地点一致后与用户城市列表比较";
        }
        return item("city", verdict, mode, criteria.cities(), sources, "CITY", "city / location / 全部 JD 明确地点", reason);
    }
    private String addressCity(String value) {
        if (value == null || value.isBlank()) return "";
        // Keep the existing Shanghai district format without treating city-named roads as cities.
        if (value.strip().matches("^上海浦[东西].*")) return "上海";
        String markedCity = match(value.strip(), "^([\\p{IsHan}]{2,4})市(?:.*)$");
        if (!markedCity.isBlank()) return city(markedCity);
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "上海", "shanghai" -> "上海";
            case "北京", "beijing" -> "北京";
            case "天津", "tianjin" -> "天津";
            default -> "";
        };
    }
    private Integer agreedRequirement(List<Integer> values) {
        return !values.contains(null) && values.stream().distinct().count() == 1 ? values.getFirst() : null;
    }
    private List<String> matches(String value, String regex) {
        var result = new ArrayList<String>();
        if (value == null) return result;
        var matcher = Pattern.compile(regex).matcher(value);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }
    private Integer integer(String value) { try { return value.isBlank()?null:Integer.valueOf(value); } catch(NumberFormatException e) { return null; } }
    private String match(String value,String regex) { if(value==null) return ""; var m=Pattern.compile(regex).matcher(value); return m.find()?m.group(1):""; }
    private Item item(String field,Verdict verdict,Strength mode,Object candidate,Object actual,String unit,String source,String reason) {
        return new Item(field,verdict,mode,String.valueOf(candidate),String.valueOf(actual),unit,"用户规则配置",source,reason);
    }
}
