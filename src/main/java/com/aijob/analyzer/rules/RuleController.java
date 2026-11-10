package com.aijob.analyzer.rules;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/rules") @Profile("!test")
public class RuleController {
    private final RuleConfigService service;
    public RuleController(RuleConfigService service) { this.service=service; }
    @GetMapping("/current") public Object current() { return service.current(); }
    @PutMapping public Object save(@RequestBody RuleModels.Save input) { return service.save(input); }
    @PostMapping("/validate") public Object validate(@RequestBody RuleModels.Save input) {
        service.validate(input);
        return Map.of("valid",true,"algorithmVersion",RuleModels.ALGORITHM);
    }
    @GetMapping("/capabilities") public Object capabilities() {
        return Map.of("algorithmVersion",RuleModels.ALGORITHM,"salaryUnits",java.util.List.of("DAY","MONTH"),
                "arrivalDateComparison",false,"unknownPolicy","CONTINUE_AND_CONFIRM","salaryConversion",false,
                "directions",java.util.List.of("JAVA_BACKEND","AI_APPLICATION","OTHER"),"periodUnit","MONTHS",
                "candidateDaysRange","1..7","monthsRange","1..60","currency","CNY");
    }
}
