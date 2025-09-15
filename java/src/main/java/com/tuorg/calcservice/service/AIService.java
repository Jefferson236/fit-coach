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
import java.util.Locale;
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
            throw new RuntimeException("DeepSeek request failed: " + resp.getStatusCode() + " - " + resp.getBody());
        }

        String respBody = resp.getBody();
        if (respBody == null || respBody.isBlank()) throw new RuntimeException("DeepSeek returned empty body");

        log.debug("Raw DeepSeek response:\n{}", respBody);

        String assistantText = extractTextFromDeepSeekResponse(respBody);
        log.debug("Assistant raw text length={} chars", (assistantText == null ? 0 : assistantText.length()));

        String cleaned = stripCodeFences(assistantText);
        log.debug("Cleaned assistant text (first 400 chars):\n{}", cleaned.length() > 400 ? cleaned.substring(0, 400) + "..." : cleaned);

        String jsonCandidate = extractFirstJsonBlock(cleaned);
        if (jsonCandidate == null) {
            if (looksLikeJson(cleaned)) jsonCandidate = cleaned.trim();
            else {
                String snippet = cleaned == null ? "" : (cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned);
                throw new RuntimeException("No pude extraer JSON de la respuesta de DeepSeek. Texto inicio: " + snippet);
            }
        }

        log.debug("JSON candidate length={} chars", jsonCandidate.length());

        GenerateResponse gr = mapper.readValue(jsonCandidate, GenerateResponse.class);

        if (gr.weeks == null) throw new RuntimeException("AI returned invalid routine (no weeks)");
        return gr;
    }

    private String buildPrompt(GenerateRequest req) {
        // intentamos obtener el peso del usuario para los ejemplos; si no existe usamos 75.0 kg por defecto
        double bw = 75.0;
        try {
            if (req != null && req.profile != null && req.profile.weight != null) {
                bw = Double.parseDouble(String.valueOf(req.profile.weight));
            }
        } catch (Exception ignored) {}

        // funciones de ejemplo: 50% y 30% del peso corporal, formateadas con un decimal y " kg"
        double ejemplo50 = Math.round(bw * 0.50 * 10.0) / 10.0;
        double ejemplo30 = Math.round(bw * 0.30 * 10.0) / 10.0;

        StringBuilder p = new StringBuilder();
        p.append("RESPONDE SÓLO con JSON válido. Sin explicaciones ni texto adicional.\n");
        p.append("IMPORTANTE: el campo \"weightFormula\" debe ser un valor numérico terminado en \" kg\" (por ejemplo \"37.5 kg\") ");
        p.append("o exactamente la cadena \"Peso corporal\" para ejercicios de peso corporal. NUNCA devuelvas expresiones (ej. \"0.5 * bodyWeight\").\n\n");

        p.append("Salida esperada (formato exacto):\n");
        p.append("{\n");
        p.append("  \"weeks\": [\n");
        p.append("    {\n");
        p.append("      \"week\": 1,\n");
        p.append("      \"days\": [\n");
        p.append("        { \"dayOfWeek\": 1, \"items\": [\n");
        p.append("          { \"exerciseId\": \"press_banca\", \"exerciseName\": \"Press de banca\", \"group\": \"Pecho\", \"sets\": 3, \"reps\": 10, \"weightFormula\": \"" + String.format(Locale.US,"%.1f kg", ejemplo50) + "\" }\n");
        p.append("        ] },\n");
        p.append("        { \"dayOfWeek\": 2, \"items\": [ { \"exerciseId\": \"rest\", \"exerciseName\": \"Descanso\", \"group\": \"rest\", \"sets\": 0, \"reps\": \"\", \"weightFormula\": \"\" } ] },\n");
        p.append("        ... hasta dayOfWeek = 7 ...\n");
        p.append("      ]\n");
        p.append("    }\n");
        p.append("  ]\n");
        p.append("}\n\n");

        p.append("REGLAS CLAVE (RESPONDER EXACTAMENTE):\n");
        p.append("1) Devuelve SOLO JSON (objetos y arrays). No uses ``` ``` ni la palabra `json` en la respuesta.\n");
        p.append("2) El campo \"weeks\" debe ser un array. Cada elemento debe tener \"week\" (int) y \"days\".\n");
        p.append("3) Cada día debe tener \"dayOfWeek\" (1..7) y \"items\" (array). Si no hay ejercicios en un día, incluye un item con exerciseId=\"rest\" como en el ejemplo.\n");
        p.append("4) Cada item debe tener los campos: exerciseId (string), exerciseName (string), group (string), sets (int), reps (int o string vacio), weightFormula (string). \n");
        p.append("5) Para ejercicios basados en porcentaje del peso corporal, calcula el número y devuelve con UNA cifra decimal seguida de \" kg\". Ejemplo con peso usuario=" + String.format(Locale.US,"%.1f kg", bw) + ":\n");
        p.append("   - 50% -> \"" + String.format(Locale.US,"%.1f kg", ejemplo50) + "\"\n");
        p.append("   - 30% -> \"" + String.format(Locale.US,"%.1f kg", ejemplo30) + "\"\n");
        p.append("6) Para dominadas, plancha y otros ejercicios de peso corporal, usa exactamente: \"Peso corporal\".\n");
        p.append("7) No añadas campos inesperados; si añades campos extra, no rompas la estructura principal.\n");
        p.append("8) Devuelve exactamente " + (req != null && req.profile != null && req.profile.durationWeeks != null ? req.profile.durationWeeks : 4) + " semanas (campo durationWeeks del usuario).\n");
        p.append("9) Máximo 4 ejercicios por día.\n\n");

        p.append("USAR SOLO esta lista de ejercicios por grupo (nombres exactos):\n");
        p.append("Pecho: Press de banca, Press inclinado con mancuernas, Aperturas con mancuernas, Fondos en paralelas\n");
        p.append("Espalda: Dominadas, Remo con barra, Peso muerto, Jalón al pecho en polea\n");
        p.append("Hombros: Press militar, Elevaciones laterales, Pájaros, Encogimientos\n");
        p.append("Bíceps: Curl con barra, Curl alternado con mancuernas, Curl en banco Scott\n");
        p.append("Tríceps: Fondos en paralelas, Extensión en polea, Press francés\n");
        p.append("Piernas: Sentadilla con barra, Prensa de pierna, Peso muerto rumano, Zancadas, Elevaciones de talones\n");
        p.append("Abdomen/Core: Crunch abdominal, Plancha, Elevaciones de piernas colgado, Rueda abdominal\n\n");

        p.append("USUARIO (valores proporcionados): ");
        if (req != null && req.profile != null) {
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
            p.append("perfil no proporcionado; usa defaults razonables (ej. 75.0 kg).");
        }

        return p.toString();
    }


    private String extractTextFromDeepSeekResponse(String respBody) throws Exception {
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
            } catch (Exception e) {
                // ignorar
            }
        }
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
