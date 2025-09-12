package com.tuorg.calcservice.controller;

import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import com.tuorg.calcservice.service.AIService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AIController {

    private final AIService aiService;

    public AIController(AIService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/generate/ai")
    public ResponseEntity<GenerateResponse> generateWithAI(@RequestBody GenerateRequest req) {
        try {
            GenerateResponse resp = aiService.generateRoutineFromAI(req);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            // Devuelve 500 con mensaje (el frontend mostrará el error)
            return ResponseEntity.status(500).body(null);
        }
    }
}
