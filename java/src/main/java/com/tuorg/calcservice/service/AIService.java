package com.tuorg.calcservice.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.text.Normalizer;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AIService {

    private final Logger log = LoggerFactory.getLogger(AIService.class);
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${deepseek.api.key}")
    private String deepseekKey;

    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final DecimalFormat KG_FMT = new DecimalFormat("#.##");

    public AIService() {
        // RestTemplate con timeouts
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(60_000);
        this.rest = new RestTemplate(requestFactory);

        // Ignorar campos desconocidos al mapear
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public GenerateResponse generateRoutineFromAI(GenerateRequest req) throws Exception {
        String prompt = buildPrompt(req);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", "deepseek-chat");
        ArrayNode messages = body.putArray("messages");

        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", "Eres un asistente que responde SOLO con JSON válido.");
        messages.add(sys);

        ObjectNode usr = mapper.createObjectNode();
        usr.put("role", "user");
        usr.put("content", prompt);
        messages.add(usr);

        body.put("max_tokens", DEFAULT_MAX_TOKENS);
        body.put("temperature", 0.2);
        body.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(this.deepseekKey);

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);

        log.debug("Calling DeepSeek with prompt length={} chars", prompt.length());
        ResponseEntity<String> resp = rest.exchange(DEEPSEEK_URL, org.springframework.http.HttpMethod.POST, entity, String.class);

        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("DeepSeek request failed: " + resp.getStatusCode() + " - " + resp.getBody());
        }

        String respBody = resp.getBody();
        if (respBody == null || respBody.isBlank()) throw new RuntimeException("DeepSeek returned empty body");

        log.debug("Raw DeepSeek response length={}", respBody.length());

        String assistantText = extractTextFromDeepSeekResponse(respBody);
        log.debug("Assistant raw text length={} chars", (assistantText == null ? 0 : assistantText.length()));

        String cleaned = stripCodeFences(assistantText);
        cleaned = unescapeIfQuotedJson(cleaned);
        log.debug("Cleaned assistant text (prefix 500): {}", cleaned == null ? "<null>" : (cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned));

        String jsonCandidate = extractFirstJsonBlock(cleaned);
        if (jsonCandidate == null) {
            if (looksLikeJson(cleaned)) jsonCandidate = cleaned.trim();
            else {
                String snippet = cleaned == null ? "" : (cleaned.length() > 1000 ? cleaned.substring(0, 1000) + "..." : cleaned);
                throw new RuntimeException("No pude extraer JSON de la respuesta de DeepSeek. Texto inicio: " + snippet);
            }
        }

        log.debug("JSON candidate length={} chars", jsonCandidate.length());

        // Normalizar la estructura para que coincida con GenerateResponse:
        // - renombrar "exercises" -> "items"
        // - convertir dayOfWeek textual -> número
        // - convertir exerciseId numérico -> string
        // - resolver weightFormula simples que usen bodyWeight
        String normalizedJson = normalizeToExpectedShape(jsonCandidate, req);
        log.debug("Normalized JSON length={}", normalizedJson.length());

        GenerateResponse gr = mapper.readValue(normalizedJson, GenerateResponse.class);
        if (gr.weeks == null) throw new RuntimeException("AI returned invalid routine (no weeks)");

        return gr;
    }

    private String buildPrompt(GenerateRequest req) {
        StringBuilder p = new StringBuilder();
        p.append("RESPONDE SÓLO con JSON válido. Sin explicaciones.\n");
        p.append("Formato estricto de salida (ejemplo):\n");
        p.append("{ \"weeks\": [ { \"week\": 1, \"days\": [ { \"dayOfWeek\": 1, \"items\": [ { \"exerciseId\": \"press_banca\", \"exerciseName\": \"Press de banca\", \"group\": \"Pecho\", \"sets\": 3, \"reps\": 10, \"weightFormula\": \"40 kg\" } ] } ] } ] }\n\n");

        p.append("USAR SOLO los ejercicios listados por grupo (no otros)...\n");
        p.append("Pecho: Press de banca, Press inclinado con mancuernas, Aperturas con mancuernas, Fondos en paralelas\n");
        p.append("Espalda: Dominadas, Remo con barra, Peso muerto, Jalón al pecho en polea\n");
        p.append("Hombros: Press militar, Elevaciones laterales, Pájaros, Encogimientos\n");
        p.append("Bíceps: Curl con barra, Curl alternado con mancuernas, Curl en banco Scott\n");
        p.append("Tríceps: Fondos en paralelas, Extensión en polea, Press francés\n");
        p.append("Piernas: Sentadilla con barra, Prensa de pierna, Peso muerto rumano, Zancadas, Elevaciones de talones\n");
        p.append("Abdomen/Core: Crunch abdominal, Plancha, Elevaciones de piernas colgado, Rueda abdominal\n\n");

        p.append("REGLAS:\n");
        p.append("- Cada item debe tener: exerciseId, exerciseName, group, sets, reps, weightFormula.\n");
        p.append("- Devuelve exactamente durationWeeks semanas solicitadas por el usuario.\n");
        p.append("- Si un día no tiene ejercicios devuelve un item con exerciseId \"rest\" y sets 0.\n");
        p.append("- weightFormula preferiblemente en kg (ej: \"37.5 kg\") o \"Peso corporal\".\n");
        p.append("- NO devuelvas texto fuera del JSON.\n\n");

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
        // fallback: body completo
        return respBody;
    }

    private String stripCodeFences(String s) {
        if (s == null) return null;
        Pattern p = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
        Matcher m = p.matcher(s);
        if (m.find()) return m.group(1).trim();
        s = s.replaceFirst("(?i)^\\s*json\\s*[:\\n]+", "");
        s = s.replaceAll("(?m)^```\\s*", "");
        s = s.replaceAll("(?m)\\s*```\\s*$", "");
        return s.trim();
    }

    private String unescapeIfQuotedJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"") && t.contains("\\\"")) {
            try {
                String unescaped = mapper.readValue(t, String.class);
                return unescaped;
            } catch (Exception e) {
                // ignore
            }
        }
        if (t.contains("\\n") || t.contains("\\\"")) {
            String attempt = t.replace("\\n", "\n").replace("\\\"", "\"");
            if (attempt.trim().startsWith("{") || attempt.trim().startsWith("[")) return attempt;
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
        if (startObj == -1) { start = startArr; openChar = '['; closeChar = ']'; }
        else if (startArr == -1) { start = startObj; openChar = '{'; closeChar = '}'; }
        else {
            if (startObj < startArr) { start = startObj; openChar = '{'; closeChar = '}'; }
            else { start = startArr; openChar = '['; closeChar = ']'; }
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
                } else if (c == '{' && openChar != '{') depth++;
                else if (c == '}' && openChar != '{') depth--;
            }
        }

        // intentar reparar añadiendo cierres
        if (!inString && depth > 0) {
            StringBuilder repaired = new StringBuilder(s.substring(start));
            for (int k = 0; k < depth; k++) repaired.append(closeChar);
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

    // ---- Normalización ----
    private String normalizeToExpectedShape(String jsonCandidate, GenerateRequest req) throws Exception {
        JsonNode root = mapper.readTree(jsonCandidate);
        ObjectNode outRoot = mapper.createObjectNode();

        ArrayNode weeksOut = mapper.createArrayNode();
        outRoot.set("weeks", weeksOut);

        JsonNode weeksNode = root.get("weeks");
        if (weeksNode == null || !weeksNode.isArray()) {
            // si la IA devolvió directamente un array -> asumirlo como weeks
            if (root.isArray()) weeksNode = root;
            else throw new RuntimeException("Estructura inesperada: no existe 'weeks'");
        }

        int weekIndex = 0;
        for (JsonNode wnode : weeksNode) {
            weekIndex++;
            ObjectNode weekOut = mapper.createObjectNode();
            // week number
            int weekNum = weekIndex;
            JsonNode wn = wnode.get("week");
            if (wn != null && wn.isInt()) weekNum = wn.asInt();
            weekOut.put("week", weekNum);

            // days
            ArrayNode daysOut = mapper.createArrayNode();
            weekOut.set("days", daysOut);

            JsonNode daysNode = wnode.get("days");
            if (daysNode == null || !daysNode.isArray()) {
                // intentar keys alternativas (p.ej. "day" o "dias")
                // si no existen, omitir
                daysNode = mapper.createArrayNode();
            }

            int dayIndex = 0;
            for (JsonNode dnode : daysNode) {
                dayIndex++;
                ObjectNode dayOut = mapper.createObjectNode();

                // dayOfWeek: aceptar número o texto
                int dow = dayIndex; // fallback
                JsonNode dowNode = dnode.get("dayOfWeek");
                if (dowNode != null) {
                    if (dowNode.isInt()) dow = dowNode.asInt();
                    else if (dowNode.isTextual()) dow = mapDayStringToNumber(dowNode.asText());
                } else {
                    // intentar campo "day" o "weekday"
                    JsonNode alt = dnode.get("day");
                    if (alt != null) {
                        if (alt.isInt()) dow = alt.asInt();
                        else if (alt.isTextual()) dow = mapDayStringToNumber(alt.asText());
                    }
                }
                dayOut.put("dayOfWeek", dow);

                // items: aceptar "items" o "exercises"
                ArrayNode itemsOut = mapper.createArrayNode();
                JsonNode itemsNode = dnode.get("items");
                if (itemsNode == null) itemsNode = dnode.get("exercises");
                if (itemsNode == null) itemsNode = dnode.get("exerciseList");
                if (itemsNode == null) itemsNode = mapper.createArrayNode();

                for (JsonNode inode : itemsNode) {
                    ObjectNode itemOut = mapper.createObjectNode();
                    // exerciseId: forzar string
                    JsonNode idn = inode.get("exerciseId");
                    if (idn == null) idn = inode.get("id");
                    if (idn != null) {
                        if (idn.isNumber()) itemOut.put("exerciseId", idn.asText());
                        else itemOut.put("exerciseId", idn.asText().trim());
                    } else {
                        // fallback: usar exerciseName slug
                        JsonNode en = inode.get("exerciseName");
                        itemOut.put("exerciseId", en == null ? "unknown" : normalizeId(en.asText()));
                    }
                    // exerciseName
                    JsonNode ename = inode.get("exerciseName");
                    if (ename == null) ename = inode.get("name");
                    itemOut.put("exerciseName", ename == null ? "" : ename.asText().trim());

                    // group
                    JsonNode group = inode.get("group");
                    if (group == null) group = inode.get("muscle") ;
                    itemOut.put("group", group == null ? "" : group.asText().trim());

                    // sets
                    JsonNode sets = inode.get("sets");
                    if (sets != null && sets.canConvertToInt()) itemOut.put("sets", sets.asInt());
                    else itemOut.put("sets", inode.has("sets") ? inode.get("sets").asInt(0) : 0);

                    // reps (permitir número o string)
                    JsonNode reps = inode.get("reps");
                    if (reps != null) {
                        if (reps.isInt() || reps.isNumber()) itemOut.put("reps", reps.asText());
                        else itemOut.put("reps", reps.asText());
                    } else itemOut.put("reps", "");

                    // weightFormula: resolver simple expresiones con bodyWeight
                    JsonNode wf = inode.get("weightFormula");
                    String wfStr = wf == null ? "" : wf.asText().trim();
                    String normalizedWf = normalizeWeightFormula(wfStr, req);
                    if (normalizedWf == null) normalizedWf = wfStr;
                    itemOut.put("weightFormula", normalizedWf);

                    // notes (opcional)
                    JsonNode notes = inode.get("notes");
                    if (notes != null) itemOut.put("notes", notes.asText());
                    else itemOut.put("notes", (String) null);

                    itemsOut.add(itemOut);
                }

                // si day no tiene items, insertar descanso
                if (itemsOut.size() == 0) {
                    ObjectNode rest = mapper.createObjectNode();
                    rest.put("exerciseId", "rest");
                    rest.put("exerciseName", "Descanso");
                    rest.put("group", "rest");
                    rest.put("sets", 0);
                    rest.put("reps", "");
                    rest.put("weightFormula", "");
                    rest.put("notes", (String) null);
                    itemsOut.add(rest);
                }

                dayOut.set("items", itemsOut);
                daysOut.add(dayOut);
            }

            weeksOut.add(weekOut);
        }

        return mapper.writeValueAsString(outRoot);
    }

    private String normalizeId(String name) {
        if (name == null) return "unknown";
        String s = name.toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = s.replaceAll("\\p{InCombiningDiacriticalMarks}+", ""); // elimina acentos
        s = s.replaceAll("\\s+", "_");                              // espacios → guiones bajos
        s = s.replaceAll("[^a-z0-9_\\-]", "");                      // limpia caracteres raros
        return s;
    }

    private int mapDayStringToNumber(String s) {
        if (s == null) return 1;
        String t = s.trim().toLowerCase(Locale.ROOT);
        Map<String, Integer> map = Map.ofEntries(
                Map.entry("monday", 1), Map.entry("lunes", 1),
                Map.entry("tuesday", 2), Map.entry("martes", 2),
                Map.entry("wednesday", 3), Map.entry("miercoles", 3), Map.entry("miércoles", 3),
                Map.entry("thursday", 4), Map.entry("jueves", 4),
                Map.entry("friday", 5), Map.entry("viernes", 5),
                Map.entry("saturday", 6), Map.entry("sabado", 6), Map.entry("sábado", 6),
                Map.entry("sunday", 7), Map.entry("domingo", 7)
        );
        return map.getOrDefault(t, // intentar números en texto
                parseIntSafe(t, 1));
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private String normalizeWeightFormula(String wf, GenerateRequest req) {
        if (wf == null) return "";
        String t = wf.trim();
        if (t.isEmpty()) return "";

        // Si ya contiene "kg" o "Peso corporal" devolver tal cual
        if (t.toLowerCase().contains("kg") || t.toLowerCase().contains("peso corporal")) return t;

        // Buscar expresiones tipo "0.5 * bodyWeight" o "bodyWeight * 0.5"
        Pattern p = Pattern.compile("([0-9]*\\.?[0-9]+)\\s*\\*\\s*(?:bodyweight|body_weight|body weight)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(t);
        if (m.find()) {
            double factor = Double.parseDouble(m.group(1));
            if (req != null && req.profile != null && req.profile.weight != null && req.profile.weight > 0) {
                double kg = factor * req.profile.weight;
                return KG_FMT.format(kg) + " kg";
            } else {
                return t; // no podemos calcular sin peso
            }
        }
        // Otro orden: bodyWeight * 0.5
        Pattern p2 = Pattern.compile("(?:bodyweight|body_weight|body weight)\\s*\\*\\s*([0-9]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(t);
        if (m2.find()) {
            double factor = Double.parseDouble(m2.group(1));
            if (req != null && req.profile != null && req.profile.weight != null && req.profile.weight > 0) {
                double kg = factor * req.profile.weight;
                return KG_FMT.format(kg) + " kg";
            } else {
                return t;
            }
        }

        // Si solo es número, añadir "kg"
        try {
            double val = Double.parseDouble(t.replaceAll("[^0-9\\.\\,]", "").replace(",", "."));
            return KG_FMT.format(val) + " kg";
        } catch (Exception ignored) {}

        return t;
    }
}
