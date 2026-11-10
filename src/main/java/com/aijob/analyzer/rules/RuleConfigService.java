package com.aijob.analyzer.rules;

import com.aijob.analyzer.demo.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.aijob.analyzer.rules.RuleModels.*;
import static com.aijob.analyzer.demo.DemoModels.USER_ID;

@Service @Profile("!test")
public class RuleConfigService {
    private final JdbcTemplate jdbc;
    private final DemoJson json;
    private final TransactionTemplate tx;
    public RuleConfigService(JdbcTemplate jdbc, DemoJson json, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.tx=new TransactionTemplate(manager);
    }
    public Snapshot current() {
        var rows=jdbc.query("SELECT * FROM demo_rule_version WHERE user_id=? ORDER BY version DESC LIMIT 1",
                (rs,n)->new Snapshot(rs.getLong("version"),ALGORITHM,rs.getString("fingerprint"),json.read(rs.getString("config_json"),Config.class)),USER_ID);
        return rows.isEmpty()?new Snapshot(0,ALGORITHM,json.fingerprint(empty()),empty()):rows.getFirst();
    }
    public Snapshot save(Save input) {
        validate(input);
        return tx.execute(status->{
            jdbc.queryForList("SELECT user_id FROM demo_profile_guard WHERE user_id=? FOR UPDATE",USER_ID);
            var current=current();
            if(current.version()!=input.expectedVersion()) throw new DemoException(409,"RULE_VERSION_CHANGED","规则已被修改，请刷新后保存");
            long next=current.version()+1;
            String hash=json.fingerprint(input.config());
            jdbc.update("INSERT INTO demo_rule_version(user_id,version,config_json,fingerprint) VALUES (?,?,?,?)",USER_ID,next,json.write(input.config()),hash);
            return new Snapshot(next,ALGORITHM,hash,input.config());
        });
    }
    public void validate(Save input) {
        if(input==null || input.expectedVersion()<0 || input.config()==null) invalid();
        var c=input.config();
        if(c.candidate()==null || c.criteria()==null || c.modes()==null) invalid();
        var candidate=c.candidate(); var criteria=c.criteria(); var m=c.modes();
        if(Arrays.asList(m.city(),m.direction(),m.attendance(),m.duration(),m.salary(),m.excludedKeywords()).contains(null)) invalid();
        if(candidate.availableDaysPerWeek()!=null && (candidate.availableDaysPerWeek()<1 || candidate.availableDaysPerWeek()>7)) invalid();
        if(candidate.availableMonths()!=null && (candidate.availableMonths()<1 || candidate.availableMonths()>60)) invalid();
        if(criteria.salaryMin()!=null && criteria.salaryMin().signum()<0 || criteria.salaryMax()!=null && criteria.salaryMax().signum()<0) invalid();
        if(criteria.salaryMin()!=null && criteria.salaryMax()!=null && criteria.salaryMin().compareTo(criteria.salaryMax())>0) invalid();
        if(criteria.salaryUnit()!=null && !Set.of("DAY","MONTH").contains(criteria.salaryUnit())) invalid();
        for(var list:List.of(criteria.cities(),criteria.directions(),criteria.excludedKeywords(),c.softNotes())) {
            if(list.size()>50 || list.stream().anyMatch(s->s==null || s.isBlank() || s.length()>255)) invalid();
        }
        if(criteria.directions().stream().anyMatch(s->!Set.of("JAVA_BACKEND","AI_APPLICATION","OTHER").contains(s))) invalid();
        if(m.city()!=Strength.DISABLED && criteria.cities().isEmpty()) invalid();
        if(m.direction()!=Strength.DISABLED && criteria.directions().isEmpty()) invalid();
        if(m.attendance()!=Strength.DISABLED && candidate.availableDaysPerWeek()==null) invalid();
        if(m.duration()!=Strength.DISABLED && candidate.availableMonths()==null) invalid();
        if(m.salary()!=Strength.DISABLED && (criteria.salaryUnit()==null || criteria.salaryMin()==null && criteria.salaryMax()==null)) invalid();
        if(m.excludedKeywords()!=Strength.DISABLED && criteria.excludedKeywords().isEmpty()) invalid();
    }
    private void invalid() { throw new DemoException(422,"RULES_INVALID","规则字段、单位或范围不合法"); }
}
