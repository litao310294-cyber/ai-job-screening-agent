package com.aijob.analyzer.demo.profile;

import com.aijob.analyzer.demo.DemoException;
import com.aijob.analyzer.profile.CurrentProfileResolver;
import com.aijob.analyzer.retrieval.KeywordRetriever;
import com.aijob.analyzer.retrieval.vector.VectorRetriever;
import com.aijob.analyzer.retrieval.vector.VectorProjectionStore;
import com.aijob.analyzer.retrieval.domain.RetrievalQuery;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import static com.aijob.analyzer.demo.DemoModels.USER_ID;
import static com.aijob.analyzer.demo.profile.ImportModels.*;

@RestController @RequestMapping("/api/profile") @Profile("!test")
public class ProfileController {
    private final ResumeImportService service;
    private final ResumeImportRepository imports;
    private final CurrentProfileResolver current;
    private final KeywordRetriever keywords;
    private final VectorRetriever vectors;
    private final VectorProjectionStore vectorStore;
    public ProfileController(ResumeImportService service, ResumeImportRepository imports, CurrentProfileResolver current,
                             KeywordRetriever keywords, VectorRetriever vectors, VectorProjectionStore vectorStore) {
        this.service = service;
        this.imports = imports;
        this.current = current;
        this.keywords = keywords;
        this.vectors = vectors;
        this.vectorStore = vectorStore;
    }
    @PostMapping(value="/resume/upload", consumes="multipart/form-data")
    public ResponseEntity<View> upload(@RequestPart("file") MultipartFile file) { return response(service.upload(file)); }
    @PostMapping("/imports/text") public View text(@Valid @RequestBody TextInput input) { return service.text(input); }
    @GetMapping("/imports/{id}") public View status(@PathVariable String id) { return imports.get(id); }
    @PostMapping("/imports/{id}/structure") public ResponseEntity<View> structure(@PathVariable String id) { return response(service.structure(id)); }
    @GetMapping("/imports/{id}/facts") public FactsView facts(@PathVariable String id) { return service.getFacts(id); }
    @PutMapping("/imports/{id}/facts") public View edit(@PathVariable String id, @Valid @RequestBody FactsInput input) { return service.edit(id, input); }
    @PostMapping("/imports/{id}/confirm") public ResponseEntity<View> confirm(@PathVariable String id, @Valid @RequestBody ConfirmInput input) { return response(service.confirm(id, input)); }
    @PostMapping("/imports/{id}/retry") public ResponseEntity<View> retry(@PathVariable String id) { return response(service.retry(id)); }
    @GetMapping("/current") public Object current() {
        var profile = current.resolveCurrent(USER_ID).orElseThrow(() -> new DemoException(409, "PROFILE_NOT_READY", "当前没有可用画像"));
        var result = new LinkedHashMap<String,Object>();
        result.put("profile", profile);
        result.put("resume", imports.forProfile(profile.artifactId()));
        result.put("persistedVectorCount", vectorStore.load(profile.identity()).size());
        return result;
    }
    @GetMapping("/search") public Object search(@RequestParam String query) {
        if (query.isBlank() || query.length() > 500) throw new DemoException(400, "INVALID_QUERY", "查询文本需为 1 至 500 字符");
        var profile = current.resolveCurrent(USER_ID).orElseThrow(() -> new DemoException(409, "PROFILE_NOT_READY", "当前没有可用画像"));
        var q = new RetrievalQuery("PROFILE_CHECK", query);
        return Map.of("profileIdentity", profile.identity(), "keywordHits", keywords.search(profile.identity(), q, 5),
                "vectorHits", vectors.search(profile.identity(), q, 5));
    }
    private ResponseEntity<View> response(View view) {
        return ResponseEntity.status(Set.of("EXTRACTING", "STRUCTURING", "INDEXING").contains(view.status()) ? 202 : 200).body(view);
    }
}
