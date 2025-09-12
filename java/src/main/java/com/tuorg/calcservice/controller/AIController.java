package com.tuorg.calcservice.controller;

import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import com.tuorg.calcservice.service.AIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AIController {

    private final Logger log = LoggerFactory.getLogger(AIController.class);
    private final AIService aiService;

    public AIController(AIService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/generate/ai")
    public ResponseEntity<?> generateAi(@RequestHeader(value = "X-API-KEY", required = false) String key,
                                        @RequestBody GenerateRequest req) {
        try {
            // Si quieres, valida aquí la API key adicionalmente (tu filtro global ya lo hace)
            GenerateResponse resp = aiService.generateRoutineFromAI(req);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            log.error("Error generando rutina AI: {}", ex.toString(), ex);
            // Devuelve mensaje legible para frontend
            return ResponseEntity.status(500).body(Map.of("error", "AI generation failed", "detail", ex.getMessage()));
        }
    }
}
