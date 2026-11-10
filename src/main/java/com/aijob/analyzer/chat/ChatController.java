package com.aijob.analyzer.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.aijob.analyzer.chat.ChatModels.*;

@RestController @RequestMapping("/api/chat") @Profile("!test") @Validated
public class ChatController {
    private final ChatService service;
    private final ChatRepository repository;
    public ChatController(ChatService service, ChatRepository repository) { this.service = service; this.repository = repository; }
    @PostMapping("/messages") public ResponseEntity<Turn> send(@Valid @RequestBody Input input) {
        var result = service.submit(input);
        return ResponseEntity.status("RUNNING".equals(result.status()) ? 202 : 200).body(result);
    }
    @GetMapping("/messages/{id}") public Turn get(@PathVariable String id) { return repository.get(id); }
    @GetMapping("/history") public History history(@RequestParam(defaultValue="0") @Min(0) long afterSequence,
            @RequestParam(defaultValue="20") @Min(1) @Max(50) int limit, @RequestParam(required=false) String jobSnapshotId,
            @RequestParam(required=false) @PositiveOrZero Long expectedGeneration) {
        return repository.history(afterSequence, limit, jobSnapshotId, expectedGeneration);
    }
    @DeleteMapping public History clear(@Valid @RequestBody Clear input) { return repository.clear(input.expectedGeneration()); }
}
