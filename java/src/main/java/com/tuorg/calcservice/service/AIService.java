package com.tuorg.calcservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AIService {

    private final Logger log = LoggerFactory.getLogger(AIService.class);
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${deepseek.api.key}")
    private String deepseekKey;

    // DeepSeek endpoint
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

        log.debug("Llamando a DeepSeek...");
        ResponseEntity<String> resp = rest.exchange(DEEPSEEK_URL, HttpMethod.POST, entity, String.class);

        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("DeepSeek request failed: " + resp.getStatusCode() + " - " + resp.getBody());
        }

        String respBody = resp.getBody();
        if (respBody == null || respBody.isBlank()) throw new RuntimeException("DeepSeek returned empty body");

        // Extraer el texto principal
        String text = extractTextFromDeepSeekResponse(respBody);
        log.debug("Texto extraído de DeepSeek (pre-trim): {}", text.length() > 400 ? text.substring(0, 400) + "..." : text);

        // Limpiar bloques de código (```json ... ```)
        text = stripCodeFences(text);

        // Intentar obtener JSON robustamente (soporta {} y [])
        String jsonCandidate = extractFirstJsonBlock(text);
        if (jsonCandidate == null) {
            // último intento: si el texto completo parece JSON
            if (looksLikeJson(text)) jsonCandidate = text;
            else throw new RuntimeException("No pude extraer JSON de la respuesta de DeepSeek. Texto: " + text);
        }

        log.debug("JSON candidate length: {}", jsonCandidate.length());
        // Parsear a GenerateResponse
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

    private String extractTextFromDeepSeekResponse(String respBody) {
        try {
            JsonNode root = mapper.readTree(respBody);
            // Rutas comunes
            String[] paths = new String[]{
                    "/choices/0/message/content",
                    "/choices/0/message/content/0",
                    "/choices/0/message/content/0/text",
                    "/choices/0/text",
                    "/choices/0/message",
                    "/choices/0/response",
                    "/output",
                    "/response"
            };
            for (String p : paths) {
                try {
                    JsonNode node = root.at(p);
                    if (!node.isMissingNode()) {
                        if (node.isTextual()) return node.asText();
                        return node.toString();
                    }
                } catch (Exception ignored) {}
            }
            // Fallback: intentar tomar choices[*].text o candidates si existe
            if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
                JsonNode c0 = root.get("choices").get(0);
                if (c0.has("text")) return c0.get("text").asText();
                if (c0.has("message")) return c0.get("message").toString();
            }
        } catch (Exception e) {
            log.debug("No JSON parseable en deepseek response, usar body raw. Error: {}", e.getMessage());
        }
        return respBody;
    }

    private String stripCodeFences(String s) {
        if (s == null) return null;
        // Quitar bloques ```json ... ``` o ``` ... ```
        Pattern p = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
        Matcher m = p.matcher(s);
        if (m.find()) {
            return m.group(1).trim();
        }
        // Quitar also single backticks (rare) and leading/trailing triple backticks without closed fence
        s = s.replaceAll("(?m)^```\\s*", "");
        s = s.replaceAll("(?m)\\s*```\\s*$", "");
        return s.trim();
    }

    private String extractFirstJsonBlock(String s) {
        if (s == null) return null;
        s = s.trim();
        // Buscar primer '{' o '['
        int startObj = s.indexOf('{');
        int startArr = s.indexOf('[');
        int start;
        char openChar;
        char closeChar;
        if (startObj == -1 && startArr == -1) return null;
        if (startObj == -1) {
            start = startArr; openChar = '['; closeChar = ']';
        } else if (startArr == -1) {
            start = startObj; openChar = '{'; closeChar = '}';
        } else {
            if (startObj < startArr) { start = startObj; openChar = '{'; closeChar = '}'; }
            else { start = startArr; openChar = '['; closeChar = ']'; }
        }

        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == openChar) {
                    depth++;
                } else if (c == closeChar) {
                    depth--;
                    if (depth == 0) {
                        // return substring including start..i
                        return s.substring(start, i + 1).trim();
                    }
                }
            }
        }
        // no encontramos cierre balanceado
        return null;
    }

    private boolean looksLikeJson(String s) {
        String t = s == null ? "" : s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }
}
