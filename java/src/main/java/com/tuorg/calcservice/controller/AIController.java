package com.tuorg.calcservice.controller;

import com.tuorg.calcservice.service.AIService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AIController {

    private final AIService aiService;

    public AIController(AIService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/generate/ai")
    public ResponseEntity<String> generateRoutineAI(@RequestBody Map<String, Object> profile) {
        String profileData = profile.toString();
        String result = aiService.generateRoutine(profileData);
        return ResponseEntity.ok(result);
    }
}
