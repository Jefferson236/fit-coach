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

import java.util.HashMap;
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

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");
        body.put("messages", List.of(
                Map.of("role", "system", "content", "Eres un asistente que responde SOLO con JSON válido."),
                Map.of("role", "user", "content", prompt)
        ));
        // Parámetros: ajusta según los límites de DeepSeek
        body.put("max_tokens", 8192);
        body.put("temperature", 0.2);
        body.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(this.deepseekKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        log.debug("Calling DeepSeek with prompt length={} chars", prompt.length());
        ResponseEntity<String> resp = rest.exchange(DEEPSEEK_URL, HttpMethod.POST, entity, String.class);

        if (!resp.getStatusCode().is2xxSuccessful()) {
            String bodyText = resp.getBody();
            log.error("DeepSeek request failed: status={} body={}", resp.getStatusCode(), bodyText);
            throw new RuntimeException("DeepSeek request failed: " + resp.getStatusCode() + " - " + bodyText);
        }

        String respBody = resp.getBody();
        log.debug("Raw DeepSeek response: {}", respBody == null ? "<empty>" : (respBody.length() > 2000 ? respBody.substring(0,2000) + "...(truncated)" : respBody));

        if (respBody == null || respBody.isBlank()) throw new RuntimeException("DeepSeek returned empty body");

        // 1) limpiar fences / prefijos
        String cleaned = stripCodeFences(respBody);

        // 2) intentar extraer texto relevante (json dentro de estructura choices/choices[0] etc.)
        String text = extractTextFromDeepSeekResponse(cleaned);
        log.debug("Extracted candidate text (pre-unescape): {}", text == null ? "<null>" : (text.length() > 1500 ? text.substring(0,1500) + "...(truncated)" : text));

        // 3) desescapar si la IA devolvió JSON como string escapado
        text = unescapeIfQuotedJson(text);
        log.debug("Candidate text after unescape (len={}): {}", text == null ? 0 : text.length(), (text == null ? "<null>" : (text.length() > 1500 ? text.substring(0,1500) + "...(truncated)" : text)));

        // 4) intentar extraer/reparar JSON
        String jsonCandidate = extractFirstJsonBlock(text);
        if (jsonCandidate == null) {
            // último intento: si el texto parece JSON suelto
            if (looksLikeJson(text)) {
                jsonCandidate = text.trim();
            } else {
                log.error("No pude extraer JSON. RespBody (truncated): {}\nCandidateText (truncated): {}",
                        respBody.length() > 2000 ? respBody.substring(0,2000) + "..." : respBody,
                        text == null ? "<null>" : (text.length() > 2000 ? text.substring(0,2000) + "..." : text));
                throw new RuntimeException("No pude extraer JSON de la respuesta de DeepSeek. Texto: " + (text == null ? respBody : text));
            }
        }

        log.debug("JSON candidate (len={}): {}", jsonCandidate.length(), jsonCandidate.length() > 2000 ? jsonCandidate.substring(0,2000) + "...(truncated)" : jsonCandidate);

        // parsear a GenerateResponse
        GenerateResponse gr;
        try {
            gr = mapper.readValue(jsonCandidate, GenerateResponse.class);
        } catch (Exception ex) {
            log.error("Error parseando JSON candidato a GenerateResponse: {}", ex.toString(), ex);
            // arrojar detalle para frontend (útil en dev)
            throw new RuntimeException("Error parseando JSON de DeepSeek: " + ex.getMessage() + ". JSON candidate starts: " +
                    (jsonCandidate.length() > 500 ? jsonCandidate.substring(0,500) + "..." : jsonCandidate));
        }

        if (gr.weeks == null) throw new RuntimeException("AI returned invalid routine (no weeks)");
        return gr;
    }

    private String buildPrompt(GenerateRequest req) {
        StringBuilder p = new StringBuilder();
        p.append("RESPONDE SÓLO con JSON válido. Sin explicaciones.\n");
        p.append("Formato: GenerateResponse -> weeks -> days -> items. Cada item: ");
        p.append("exerciseId, exerciseName, group, sets, reps, weightFormula (si aplica).\n");
        p.append("Usa SOLO los siguientes ejercicios por grupo:\n");
        p.append("Pecho: Press de banca, Press inclinado con mancuernas, Aperturas con mancuernas, Fondos en paralelas\n");
        p.append("Espalda: Dominadas, Remo con barra, Peso muerto, Jalón al pecho en polea\n");
        p.append("Hombros: Press militar, Elevaciones laterales, Pájaros, Encogimientos\n");
        p.append("Bíceps: Curl con barra, Curl alternado con mancuernas, Curl en banco Scott\n");
        p.append("Tríceps: Fondos en paralelas, Extensión en polea, Press francés\n");
        p.append("Piernas: Sentadilla con barra, Prensa de pierna, Peso muerto rumano, Zancadas, Elevaciones de talones\n");
        p.append("Abdomen/Core: Crunch abdominal, Plancha, Elevaciones de piernas colgado, Rueda abdominal\n\n");

        p.append("REGLAS IMPORTANTES:\n");
        p.append(" - Máximo 4 ejercicios por día.\n");
        p.append(" - Devuelve exactamente durationWeeks semanas.\n");
        p.append(" - Cada día debe tener 'items' (aunque sea vacío) y cada item los campos requeridos.\n");
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
        JsonNode root = mapper.readTree(respBody);
        List<String> tryPaths = List.of(
                "/choices/0/message/content",
                "/choices/0/message/content/0",
                "/choices/0/text",
                "/choices/0/message/0/content",
                "/outputs/0",
                "/output",
                "/response",
                "/choices/0/response",
                "/candidates/0/content/parts/0/text",
                "/candidates/0/text"
        );
        for (String p : tryPaths) {
            try {
                JsonNode node = root.at(p);
                if (!node.isMissingNode()) {
                    if (node.isTextual()) return node.asText();
                    return node.toString();
                }
            } catch (Exception ignored) {}
        }
        // Si la raíz es texto simple
        if (root.isTextual()) return root.asText();
        // fallback: devolver el body entero
        return respBody;
    }

    private String stripCodeFences(String s) {
        if (s == null) return null;
        // Quitar bloques ```json ... ```
        Pattern p = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
        Matcher m = p.matcher(s);
        if (m.find()) return m.group(1).trim();
        // Quitar encabezados como "json\n" o "JSON\n"
        s = s.replaceFirst("(?i)^\\s*json\\s*[:\\n]+", "");
        // Quitar fences simples si quedan
        s = s.replaceAll("(?m)^```\\s*", "");
        s = s.replaceAll("(?m)\\s*```\\s*$", "");
        return s.trim();
    }

    /**
     * Si el texto contiene JSON escapado (\" y \\n), lo desescapa.
     * También maneja casos sin comillas exteriores pero con muchos escapes.
     */
    private String unescapeIfQuotedJson(String s) {
        if (s == null) return null;
        String t = s.trim();

        // Caso 1: empieza y termina con comillas y contiene escapes internos -> quitar comillas externas y desescapar
        if (t.startsWith("\"") && t.endsWith("\"") && t.contains("\\\"")) {
            t = t.substring(1, t.length()-1);
            t = t.replaceAll("\\\\n", "\n")
                    .replaceAll("\\\\r", "\r")
                    .replaceAll("\\\\t", "\t")
                    .replaceAll("\\\\\"", "\"")
                    .replaceAll("\\\\\\\\", "\\\\");
            return t.trim();
        }

        // Caso 2: no está entre comillas, pero contiene muchos escapes (heurística)
        long backslashes = t.chars().filter(ch -> ch == '\\').count();
        long quotes = t.chars().filter(ch -> ch == '\"').count();
        if (backslashes > quotes && (t.contains("\\\"weeks\\\"") || t.contains("\\\"days\\\"") || t.contains("\\\\n"))) {
            t = t.replaceAll("\\\\n", "\n")
                    .replaceAll("\\\\r", "\r")
                    .replaceAll("\\\\t", "\t")
                    .replaceAll("\\\\\"", "\"")
                    .replaceAll("\\\\\\\\", "\\\\");
            return t.trim();
        }

        return s;
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
                    if (depth == 0) return s.substring(start, i+1).trim();
                }
            }
        }

        // truncado: intentar reparar
        if (!inString && depth > 0) {
            StringBuilder repaired = new StringBuilder(s.substring(start));
            for (int k=0;k<depth;k++) repaired.append(closeChar);
            String cand = repaired.toString().trim();
            try {
                mapper.readTree(cand);
                return cand;
            } catch (Exception e) {
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
