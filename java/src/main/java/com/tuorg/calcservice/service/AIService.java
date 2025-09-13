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
        // parámetros configurables
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

        // 1) Extraer el texto real generado por el asistente (desde choices/... o candidates/...)
        String assistantText = extractTextFromDeepSeekResponse(respBody);
        log.debug("Assistant raw text length={} chars", (assistantText == null ? 0 : assistantText.length()));

        // 2) Limpiar fences / encabezados (```json ... ``` y prefijos "json\n")
        String cleaned = stripCodeFences(assistantText);
        log.debug("Cleaned assistant text (first 400 chars):\n{}", cleaned.length() > 400 ? cleaned.substring(0, 400) + "..." : cleaned);

        // 3) Extraer primer bloque JSON válido (balanceando llaves)
        String jsonCandidate = extractFirstJsonBlock(cleaned);
        if (jsonCandidate == null) {
            // último recurso: si el cleaned parece JSON perfecto, úsalo
            if (looksLikeJson(cleaned)) jsonCandidate = cleaned.trim();
            else {
                // incluir una porción del cleaned para ayudar debugging
                String snippet = cleaned == null ? "" : (cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned);
                throw new RuntimeException("No pude extraer JSON de la respuesta de DeepSeek. Texto inicio: " + snippet);
            }
        }

        log.debug("JSON candidate length={} chars", jsonCandidate.length());

        // 4) Parsear a GenerateResponse
        GenerateResponse gr = mapper.readValue(jsonCandidate, GenerateResponse.class);

        if (gr.getWeeks() == null) throw new RuntimeException("AI returned invalid routine (no weeks)");
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

    /**
     * Extrae el contenido textual que generó el asistente dentro de la respuesta de DeepSeek.
     * Devuelve asText() (es decir, con escapes interpretados) cuando sea posible.
     */
    private String extractTextFromDeepSeekResponse(String respBody) throws Exception {
        JsonNode root = mapper.readTree(respBody);

        // Rutas probables en diferentes APIs (DeepSeek/OpenAI-like)
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
                    // Si es texto, devolverlo con asText() para que los escapes se conviertan a newlines
                    if (node.isTextual()) return node.asText();
                    // Si es array de partes -> concatenar sus textos si existen
                    if (node.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode n : node) {
                            if (n.isTextual()) sb.append(n.asText()).append("\n");
                            else sb.append(n.toString()).append("\n");
                        }
                        return sb.toString().trim();
                    }
                    // fallback: si es objeto, devolver su toString()
                    return node.toString();
                }
            } catch (Exception e) {
                // ignora y prueba la siguiente ruta
            }
        }

        // Si no encontramos ninguna ruta especial, devolver el body entero
        // (en muchos casos la respuesta completa seguirá siendo JSON que contiene el texto)
        return respBody;
    }

    /**
     * Quita fences de Markdown ```json ... ``` y prefijos "json\n" o "JSON\n"
     */
    private String stripCodeFences(String s) {
        if (s == null) return null;
        // Si el texto contiene fences reales (```json ... ```), capturarlos
        Pattern p = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
        Matcher m = p.matcher(s);
        if (m.find()) return m.group(1).trim();

        // Si viene con prefijo "json\n" o "JSON\n"
        s = s.replaceFirst("(?i)^\\s*json\\s*[:\\n]+", "");

        // remover fences residuales
        s = s.replaceAll("(?m)^```\\s*", "");
        s = s.replaceAll("(?m)\\s*```\\s*$", "");
        return s.trim();
    }

    /**
     * Extrae el primer bloque JSON balanceado (desde '{' o '[' hasta su cierre correspondiente).
     * Trata de "reparar" añadiendo llaves de cierre si la respuesta fue truncada.
     */
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

        // si depth>0 intentamos reparar añadiendo cierres (solo si no estamos dentro de string)
        if (!inString && depth > 0) {
            StringBuilder repaired = new StringBuilder(s.substring(start));
            for (int k=0;k<depth;k++) repaired.append(closeChar);
            String cand = repaired.toString().trim();
            try {
                mapper.readTree(cand); // si parsea OK, lo devolvemos
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
