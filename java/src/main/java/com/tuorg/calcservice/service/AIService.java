package com.tuorg.calcservice.service;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class AIService {

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private static final String API_KEY = "TU_API_KEY_AQUI"; // ⚠️ luego lo pasamos a application.properties

    public String generateRoutine(String profileData) {
        RestTemplate restTemplate = new RestTemplate();

        // Construimos el body con el prompt
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text",
                                        "Genera una rutina de gimnasio personalizada en formato JSON válido con semanas, días y ejercicios. " +
                                                "El perfil del usuario es: " + profileData)
                        ))
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-goog-api-key", API_KEY);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                GEMINI_API_URL + "?key=" + API_KEY,
                HttpMethod.POST,
                entity,
                Map.class
        );

        // Gemini devuelve nested → contents → candidates → parts → text
        return response.getBody().toString();
    }
}
