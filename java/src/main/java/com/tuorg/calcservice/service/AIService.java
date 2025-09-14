package com.tuorg.calcservice.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AIService {

    private final Logger log = LoggerFactory.getLogger(AIService.class);
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${deepseek.api.key}")
    private String deepseekKey;

    // DeepSeek endpoint
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";

    // tokens por defecto (ajusta si quieres)
    private static final int DEFAULT_MAX_TOKENS = 4096;

    public AIService() {
        // Inicializar RestTemplate con timeouts para evitar bloqueos largos
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000); // 10s connect
        requestFactory.setReadTimeout(60_000);    // 60s read
        this.rest = new RestTemplate(requestFactory);

        // Configurar mapper para ignorar propiedades desconocidas (evita errores si la IA añade campos extras)
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public GenerateResponse generateRoutineFromAI(GenerateRequest req) throws Exception {
        String prompt = buildPrompt(req);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");
        body.put("messages", List.of(
                Map.of("role", "system", "content", "Eres un asistente que responde SOLO con JSON válido."),
                Map.of("role", "user", "content", prompt)
        ));
        // reducir tokens por defecto para evitar respuestas gigantes/truncadas y uso excesivo
        body.put("max_tokens", DEFAULT_MAX_TOKENS);
        body.put("temperature", 0.2);
        body.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(this.deepseekKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        log.debug("Calling DeepSeek with prompt length={} chars", prompt.length());
        ResponseEntity<String> resp = rest.exchange(DEEPSEEK_URL, HttpMethod.POST, entity, String.class);

        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("DeepSeek request failed: " + resp.getStatusCode() + " - " + resp.getBody());
        }

        String respBody = resp.getBody();
        if (respBody == null || respBody.isBlank()) throw new RuntimeException("DeepSeek returned empty body");

        log.debug("Raw DeepSeek response length={}", respBody.length());

        // 1) extraer texto del body (choices, candidates, outputs, ...)
        String assistantText = extractTextFromDeepSeekResponse(respBody);
        log.debug("Assistant raw text length={} chars", (assistantText == null ? 0 : assistantText.length()));

        // 2) quitar fences ```json``` y encabezados "json"
        String cleaned = stripCodeFences(assistantText);
        log.debug("Cleaned assistant text (first 500):\n{}", cleaned == null ? "<null>" :
                (cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned));

        // 3) si viene doblemente codificado como string con escapes -> des-escapar
        cleaned = unescapeIfQuotedJson(cleaned);

        // 4) extraer primer bloque JSON balanceado
        String jsonCandidate = extractFirstJsonBlock(cleaned);
        if (jsonCandidate == null) {
            if (looksLikeJson(cleaned)) jsonCandidate = cleaned.trim();
            else {
                String snippet = cleaned == null ? "" : (cleaned.length() > 1000 ? cleaned.substring(0, 1000) + "..." : cleaned);
                throw new RuntimeException("No pude extraer JSON de la respuesta de DeepSeek. Texto inicio: " + snippet);
            }
        }

        log.debug("JSON candidate length={} chars", jsonCandidate.length());

        // 5) parsear a DTO (mapper ignora propiedades desconocidas)
        GenerateResponse gr = mapper.readValue(jsonCandidate, GenerateResponse.class);

        if (gr.weeks == null) throw new RuntimeException("AI returned invalid routine (no weeks)");
        return gr;
    }

    private String buildPrompt(GenerateRequest req) {
        StringBuilder p = new StringBuilder();
        p.append("RESPONDE SÓLO con JSON válido. Sin explicaciones.\n");
        p.append("Formato estricto de salida:\n");
        p.append("{ \"weeks\": [ ... ] }\n\n");

        p.append("USAR SOLO los siguientes ejercicios por grupo:\n");
        p.append("Pecho: Press de banca, Press inclinado con mancuernas, Aperturas con mancuernas, Fondos en paralelas\n");
        p.append("Espalda: Dominadas, Remo con barra, Peso muerto, Jalón al pecho en polea\n");
        p.append("Hombros: Press militar, Elevaciones laterales, Pájaros, Encogimientos\n");
        p.append("Bíceps: Curl con barra, Curl alternado con mancuernas, Curl en banco Scott\n");
        p.append("Tríceps: Fondos en paralelas, Extensión en polea, Press francés\n");
        p.append("Piernas: Sentadilla con barra, Prensa de pierna, Peso muerto rumano, Zancadas, Elevaciones de talones\n");
        p.append("Abdomen/Core: Crunch abdominal, Plancha, Elevaciones de piernas colgado, Rueda abdominal\n\n");

        p.append("REGLAS IMPORTANTES:\n");
        p.append(" - Incluye el campo \"week\" y \"dayOfWeek\".\n");
        p.append(" - Cada semana debe tener EXACTAMENTE durationWeeks semanas solicitadas.\n");
        p.append(" - Si un día no tiene ejercicios, devuelve un item con \"exerciseId\": \"rest\" (Descanso).\n");
        p.append(" - Cada item debe tener: exerciseId, exerciseName, group, sets, reps, weightFormula.\n");
        p.append(" - El campo weightFormula debe contener el valor ya calculado en kg cuando corresponda, p.ej. \"37.5 kg\" o \"Peso corporal\" para ejercicios de propio peso.\n");
        p.append(" - No uses explicaciones ni texto fuera del JSON.\n\n");

        p.append("Usuario: ");
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
            p.append("perfil no proporcionado, usa valores por defecto.");
        }
        return p.toString();
    }

    private String extractTextFromDeepSeekResponse(String respBody) throws Exception {
        // Intentar parsear la respuesta como JSON y buscar en varios caminos comunes
        JsonNode root = mapper.readTree(respBody);
        List<String> tryPaths = List.of(
                "/choices/0/message/content",
                "/choices/0/message/content/0",
                "/choices/0/text",
                "/choices/0/message/content/0/text",
                "/outputs/0",
                "/output",
                "/response",
                "/choices/0/response",
                "/candidates/0/content/parts/0/text",
                "/candidates/0/content/0/parts/0/text"
        );

        for (String p : tryPaths) {
            try {
                JsonNode node = root.at(p);
                if (!node.isMissingNode()) {
                    if (node.isTextual()) return node.asText();
                    if (node.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode n : node) {
                            if (n.isTextual()) sb.append(n.asText()).append("\n");
                            else sb.append(n.toString()).append("\n");
                        }
                        return sb.toString().trim();
                    }
                    return node.toString();
                }
            } catch (Exception ignored) {}
        }

        // Intentos alternativos para estructuras menos comunes
        try {
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode msg = choices.get(0).get("message");
                if (msg != null) {
                    JsonNode content = msg.get("content");
                    if (content != null) {
                        if (content.isTextual()) return content.asText();
                        if (content.isArray() && content.size() > 0) {
                            for (JsonNode part : content) {
                                if (part.isTextual()) return part.asText();
                                if (part.has("text")) return part.get("text").asText();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Si no se encontró nada, devolver el body completo (para debug)
        return respBody;
    }

    private String stripCodeFences(String s) {
        if (s == null) return null;
        // quitar bloques ```json ... ```
        Pattern p = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
        Matcher m = p.matcher(s);
        if (m.find()) return m.group(1).trim();
        // quitar encabezados como "json\n"
        s = s.replaceFirst("(?i)^\\s*json\\s*[:\\n]+", "");
        s = s.replaceAll("(?m)^```\\s*", "");
        s = s.replaceAll("(?m)\\s*```\\s*$", "");
        return s.trim();
    }

    private String unescapeIfQuotedJson(String text) {
        if (text == null) return null;
        String t = text.trim();

        // Caso: string entre comillas y con escapes -> p.ej "\"{\\\"weeks\\\":...}\""
        if (t.length() > 2 && t.startsWith("\"") && t.endsWith("\"") && t.contains("\\\"")) {
            try {
                String unescaped = mapper.readValue(t, String.class);
                log.debug("Unescaped JSON string (beforeLen={}, afterLen={})", t.length(), unescaped.length());
                return unescaped;
            } catch (Exception e) {
                log.debug("No se pudo unescapear texto JSON-string: {}", e.toString());
            }
        }

        // Reemplazo simple de escapes comunes si detectados
        if (t.contains("\\n") || t.contains("\\\"")) {
            String attempt = t.replace("\\n", "\n").replace("\\\"", "\"");
            if (attempt.trim().startsWith("{") || attempt.trim().startsWith("[")) {
                return attempt;
            }
        }

        return t;
    }

    private String extractFirstJsonBlock(String s) {
        if (s == null) return null;
        s = s.trim();

        int startObj = s.indexOf('{');
        int startArr = s.indexOf('[');
        if (startObj == -1 && startArr == -1) return null;

        int start;
        char openChar;
        char closeChar;
        if (startObj == -1) { start = startArr; openChar='['; closeChar=']'; }
        else if (startArr == -1) { start = startObj; openChar='{'; closeChar='}'; }
        else {
            if (startObj < startArr) { start = startObj; openChar='{'; closeChar='}'; }
            else { start = startArr; openChar='['; closeChar=']'; }
        }

        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                if (c == openChar) depth++;
                else if (c == closeChar) {
                    depth--;
                    if (depth == 0) return s.substring(start, i + 1).trim();
                } else if (c == '{' && openChar!='{') depth++;
                else if (c == '}' && openChar!='{') depth--;
            }
        }

        // intentar reparar JSON truncado cerrando llaves (solo si no estamos dentro de string)
        if (!inString && depth > 0) {
            StringBuilder repaired = new StringBuilder(s.substring(start));
            for (int k = 0; k < depth; k++) repaired.append(closeChar);
            String cand = repaired.toString().trim();
            try {
                mapper.readTree(cand);
                return cand;
            } catch (Exception e) {
                log.debug("No se pudo reparar JSON candidate: {}", e.toString());
                return null;
            }
        }
        return null;
    }

    private boolean looksLikeJson(String s) {
        String t = s == null ? "" : s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }
}
