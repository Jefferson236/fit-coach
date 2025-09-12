package com.tuorg.calcservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class AIService {

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${deepseek.api.key}")
    private String deepseekKey;

    // DeepSeek endpoint (por el curl que compartiste)
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";

    public GenerateResponse generateRoutineFromAI(GenerateRequest req) throws Exception {
        String prompt = buildPrompt(req);

        Map<String, Object> body = Map.of(
                "model", "deepseek-chat",
                "messages", List.of(
                        Map.of("role", "system", "content", "Eres un asistente que responde SOLO con JSON válido"),
                        Map.of("role", "user", "content", prompt)
                ),
                "stream", false
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(this.deepseekKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> resp = rest.exchange(DEEPSEEK_URL, HttpMethod.POST, entity, String.class);

        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("DeepSeek request failed: " + resp.getStatusCode() + " - " + resp.getBody());
        }

        String respBody = resp.getBody();
        if (respBody == null || respBody.isBlank()) throw new RuntimeException("DeepSeek returned empty body");

        // Extraer texto útil de la respuesta (varios caminos posibles)
        String text = extractTextFromDeepSeekResponse(respBody);

        // Ahora extraer JSON dentro del texto (trimToJson) e parsear a GenerateResponse
        String jsonCandidate = trimToJson(text);
        if (jsonCandidate == null) {
            // último recurso: si el texto es JSON completo
            if (looksLikeJson(text)) jsonCandidate = text;
            else throw new RuntimeException("No pude extraer JSON de la respuesta de DeepSeek. Texto: " + text);
        }

        GenerateResponse gr = mapper.readValue(jsonCandidate, GenerateResponse.class);
        if (gr.weeks == null) throw new RuntimeException("AI returned invalid routine (no weeks)");

        return gr;
    }

    private String buildPrompt(GenerateRequest req) {
        StringBuilder p = new StringBuilder();
        p.append("Genera únicamente JSON válido (sin texto extra) que represente una rutina de entrenamiento ");
        p.append("en el formato Java GenerateResponse (weeks -> days -> items). ");
        p.append("Cada item debe incluir al menos: exerciseId (o exerciseName), exerciseName, group, sets, reps, weightFormula (si aplica). ");
        p.append("Usa SOLO los ejercicios de la siguiente lista por grupo:\n");
        p.append("Pecho: Press de banca, Press inclinado con mancuernas, Aperturas con mancuernas, Fondos en paralelas\n");
        p.append("Espalda: Dominadas, Remo con barra, Peso muerto, Jalón al pecho en polea\n");
        p.append("Hombros: Press militar, Elevaciones laterales, Pájaros, Encogimientos\n");
        p.append("Bíceps: Curl con barra, Curl alternado con mancuernas, Curl en banco Scott\n");
        p.append("Tríceps: Fondos en paralelas, Extensión en polea, Press francés\n");
        p.append("Piernas: Sentadilla con barra, Prensa de pierna, Peso muerto rumano, Zancadas, Elevaciones de talones\n");
        p.append("Abdomen/Core: Crunch abdominal, Plancha, Elevaciones de piernas colgado, Rueda abdominal\n");

        p.append("\nUsuario: ");
        if (req.profile != null) {
            p.append("name=").append(req.profile.name).append(", ");
            p.append("age=").append(req.profile.age).append(", ");
            p.append("sex=").append(req.profile.sex).append(", ");
            p.append("weight=").append(req.profile.weight).append("kg, ");
            p.append("heightCm=").append(req.profile.heightCm).append("cm, ");
            p.append("level=").append(req.profile.level).append(", ");
            p.append("goal=").append(req.profile.goal).append(", ");
            p.append("split=").append(req.profile.split).append(", ");
            p.append("durationWeeks=").append(req.profile.durationWeeks == null ? 4 : req.profile.durationWeeks).append(".");
        } else {
            p.append("perfil no proporcionado, usa valores sensatos por defecto.");
        }

        p.append("\nREGLAS:\n");
        p.append(" - Genera el número de semanas igual a durationWeeks.\n");
        p.append(" - Para cada semana genera días según el split (fullbody=3, upper-lower=4, push-pull-legs=3).\n");
        p.append(" - Realiza la salida como JSON EXACTO que mapee a GenerateResponse. No agregues texto adicional.\n");

        return p.toString();
    }

    private String extractTextFromDeepSeekResponse(String respBody) throws Exception {
        JsonNode root = mapper.readTree(respBody);

        // Intentar rutas comunes tipo OpenAI/DeepSeek
        List<String> tryPaths = List.of(
                "/choices/0/message/content",
                "/choices/0/message/content/0",
                "/choices/0/text",
                "/output",
                "/response",
                "/choices/0/response"
        );

        for (String p : tryPaths) {
            try {
                JsonNode node = root.at(p);
                if (!node.isMissingNode()) {
                    if (node.isTextual()) return node.asText();
                    // si es array o objeto, convertir a string
                    return node.toString();
                }
            } catch (Exception ignored) {}
        }

        // Si no hay nodos claros, devolver el body entero
        return respBody;
    }

    // ---------- helper methods (trimToJson, looksLikeJson) ----------
    private boolean looksLikeJson(String s) {
        String t = s == null ? "" : s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    private String trimToJson(String s) {
        if (s == null) return null;
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
