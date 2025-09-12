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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class AIService {

    private final Logger log = LoggerFactory.getLogger(AIService.class);

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${gemini.api.key:}")
    private String geminiKey;

    private final RoutineGeneratorService fallbackGenerator;

    // URL (modelo gemini-2.0-flash)
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    public AIService(RoutineGeneratorService fallbackGenerator) {
        this.fallbackGenerator = fallbackGenerator;
    }

    /**
     * Intenta generar rutina con Gemini. Si hay fallo recuperable, cae al generador local.
     */
    public GenerateResponse generateRoutineFromAI(GenerateRequest req) throws Exception {
        if (geminiKey == null || geminiKey.isBlank()) {
            log.warn("gemini.api.key no configurada — usando generator local como fallback");
            return fallbackGenerator.generate(req);
        }

        String prompt = buildPrompt(req);

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-goog-api-key", this.geminiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        String urlWithKey = GEMINI_URL + "?key=" + this.geminiKey;

        try {
            log.debug("Enviando petición a Gemini (url={}): prompt length={}", GEMINI_URL, prompt.length());
            ResponseEntity<String> resp = rest.exchange(urlWithKey, HttpMethod.POST, entity, String.class);

            if (!resp.getStatusCode().is2xxSuccessful()) {
                String errBody = resp.getBody();
                log.error("Gemini no 2xx: {} - body: {}", resp.getStatusCode(), errBody);
                throw new RuntimeException("Gemini returned non-2xx: " + resp.getStatusCode());
            }

            String respBody = resp.getBody();
            log.debug("Respuesta cruda de Gemini: ({} chars)", respBody == null ? 0 : respBody.length());

            if (respBody == null || respBody.isBlank()) {
                log.error("Gemini devolvió body vacío");
                throw new RuntimeException("Gemini returned empty body");
            }

            String jsonCandidate = extractJsonFromGeminiResponse(respBody);
            log.debug("JSON extraído de Gemini: {}", jsonCandidate);

            GenerateResponse gr = mapper.readValue(jsonCandidate, GenerateResponse.class);

            if (gr.weeks == null || gr.weeks.isEmpty()) {
                log.warn("Gemini devolvió estructura vacía o sin semanas, usando fallback local");
                return fallbackGenerator.generate(req);
            }

            return gr;

        } catch (RestClientException rce) {
            log.error("Error de conexión o cliente al llamar Gemini: {}", rce.toString(), rce);
            // Fallback local
            return fallbackGenerator.generate(req);
        } catch (Exception ex) {
            // Si el parseo JSON o cualquier otra cosa falla, registrarlo y usar fallback.
            log.error("Error procesando respuesta de Gemini: {}", ex.toString(), ex);
            return fallbackGenerator.generate(req);
        }
    }

    private String buildPrompt(GenerateRequest req) throws Exception {
        StringBuilder p = new StringBuilder();

        p.append("IMPORTANTE: devuelve SOLO JSON válido, sin explicación ni texto adicional.\n");
        p.append("La salida debe seguir exactamente este esquema (JSON):\n");
        p.append("{\n");
        p.append("  \"title\": \"string opcional\",\n");
        p.append("  \"weeks\": [ { \"week\": number, \"days\": [ { \"dayOfWeek\": number, \"items\": [ { \"exerciseId\": string_or_number, \"exerciseName\": string, \"group\": string, \"sets\": number, \"reps\": string, \"weightFormula\": string, \"notes\": string } ] } ] } ]\n");
        p.append("}\n\n");

        p.append("Reglas:\n");
        p.append("- Genera exactamente durationWeeks semanas.\n");
        p.append("- Días/semana según split: fullbody=>3, upper-lower=>4, push-pull-legs=>3, default=>3.\n");
        p.append("- Usa nombres en español: Sentadilla, Press Banca, Remo, Peso Muerto, Plancha, Curl, Fondos, etc.\n");
        p.append("- Ajusta sets/reps según goal (fuerza/hipertrofia/resistencia) y level.\n");
        p.append("- Cada item debe incluir exerciseId (slug o id), exerciseName, group, sets, reps, weightFormula; notes opcional.\n");
        p.append("- No añadas texto fuera del JSON.\n\n");

        p.append("Perfil (JSON):\n");
        if (req != null && req.profile != null) {
            p.append(mapper.writeValueAsString(req.profile)).append("\n\n");
        } else {
            p.append("{\"note\":\"perfil no proporcionado\"}\n\n");
        }

        p.append("Devuelve SOLO JSON válido acorde al esquema.\n");

        return p.toString();
    }

    private String extractJsonFromGeminiResponse(String respBody) throws Exception {
        // Try parse as JSON
        try {
            JsonNode root = mapper.readTree(respBody);

            // Common candidate paths
            String[] paths = new String[] {
                    "/candidates/0/content/parts/0/text",
                    "/candidates/0/output/0/content/parts/0/text",
                    "/candidates/0/content/0/parts/0/text",
                    "/candidates/0/parts/0/text",
                    "/outputs/0/text",
                    "/candidates/0/text",
                    "/response",
                    "/output"
            };

            for (String p : paths) {
                JsonNode n = root.at(p);
                if (!n.isMissingNode() && n.isTextual()) {
                    String txt = n.asText();
                    String json = trimToJson(txt);
                    if (json != null) return json;
                    if (looksLikeJson(txt)) return txt;
                }
            }

        } catch (Exception ignore) {
            // not strict JSON, continue to heuristics
        }

        // Heuristic: find first balanced { ... } block
        String candidate = trimToJson(respBody);
        if (candidate != null) return candidate;

        if (looksLikeJson(respBody)) return respBody;

        throw new RuntimeException("No JSON válido encontrado en respuesta de Gemini");
    }

    private boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
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
