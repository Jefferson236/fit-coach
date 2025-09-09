package com.tuorg.calcservice.controller;

import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import com.tuorg.calcservice.service.RoutineGeneratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RoutineGeneratorController {

<<<<<<< HEAD
=======
    private final RoutineGeneratorService generator;

    public RoutineGeneratorController(RoutineGeneratorService generator) {
        this.generator = generator;
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest req) {
        GenerateResponse resp = generator.generate(req);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/preview")
    public ResponseEntity<GenerateResponse> preview(@RequestBody GenerateRequest req) {
        // same behavior for now
        GenerateResponse resp = generator.generate(req);
        return ResponseEntity.ok(resp);
    }
>>>>>>> 5a5b5cb1d7626faf7b620491c8aa496d5a3d416c
}
