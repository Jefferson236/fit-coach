package com.tuorg.calcservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
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

    // Lista de ejercicios permitidos (útil para inferir grupo si hiciera falta)
    private static final Map<String, String> EX_TO_GROUP = Map.ofEntries(
            Map.entry("Press de banca", "Pecho"),
            Map.entry("Press inclinado con mancuernas", "Pecho"),
            Map.entry("Aperturas con mancuernas", "Pecho"),
            Map.entry("Fondos en paralelas", "Pecho"),

            Map.entry("Dominadas", "Espalda"),
            Map.entry("Remo con barra", "Espalda"),
            Map.entry("Peso muerto", "Espalda"),
            Map.entry("Jalón al pecho en polea", "Espalda"),

            Map.entry("Press militar", "Hombros"),
            Map.entry("Elevaciones laterales", "Hombros"),
            Map.entry("Pájaros", "Hombros"),
            Map.entry("Encogimientos", "Hombros"),

            Map.entry("Curl con barra", "Bíceps"),
            Map.entry("Curl alternado con mancuernas", "Bíceps"),
            Map.entry("Curl en banco Scott", "Bíceps"),

            Map.entry("Fondos en paralelas", "Tríceps"),
            Map.entry("Extensión en polea", "Tríceps"),
            Map.entry("Press francés", "Tríceps"),

            Map.entry("Sentadilla con barra", "Piernas"),
            Map.entry("Prensa de pierna", "Piernas"),
            Map.entry("Peso muerto rumano", "Piernas"),
            Map.entry("Zancadas", "Piernas"),
            Map.entry("Elevaciones de talones", "Piernas"),

            Map.entry("Crunch abdominal", "Abdomen/Core"),
            Map.entry("Plancha", "Abdomen/Core"),
            Map.entry("Elevaciones de piernas colgado", "Abdomen/Core"),
            Map.entry("Rueda abdominal", "Abdomen/Core")
    );

    public GenerateResponse generateRoutineFromAI(GenerateRequest req) throws Exception {
        String prompt = buildPrompt(req);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");
        body.put("messages", List.of(
                Map.of("role", "system", "content", "Eres un asistente que responde SOLO con JSON válido."),
                Map.of("role", "user", "content", prompt)
        ));
        body.put("max_tokens", 8192);
        body.put("temperature", 0.0); // más determinista
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

        // 1) extraer el texto que el assistant devolvió
        String assistantText = extractTextFromDeepSeekResponse(respBody);
        log.debug("Assistant raw text length={} chars", (assistantText == null ? 0 : assistantText.length()));

        // 2) limpiar fences
        String cleaned = stripCodeFences(assistantText);
        log.debug("Cleaned assistant text (first 400 chars):\n{}", cleaned.length() > 400 ? cleaned.substring(0, 400) + "..." : cleaned);

        // 3) extraer bloque JSON (reparar truncamientos simples)
        String jsonCandidate = extractFirstJsonBlock(cleaned);
        if (jsonCandidate == null) {
            if (looksLikeJson(cleaned)) jsonCandidate = cleaned.trim();
            else {
                String snippet = cleaned == null ? "" : (cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned);
                throw new RuntimeException("No pude extraer JSON de la respuesta de DeepSeek. Texto inicio: " + snippet);
            }
        }

        log.debug("JSON candidate length={} chars", jsonCandidate.length());

        // 4) normalizar la estructura para que encaje con GenerateResponse
        String normalized = normalizeJsonForGenerateResponse(jsonCandidate, req);
        log.debug("Normalized JSON length={} chars, sample: {}", normalized.length(), normalized.length() > 400 ? normalized.substring(0,400) + "..." : normalized);

        // 5) mapear a DTO
        GenerateResponse gr = mapper.readValue(normalized, GenerateResponse.class);

        if (gr.weeks == null) throw new RuntimeException("AI returned invalid routine (no weeks)");
        return gr;
    }

    // ------------------ prompt (dejamos el prompt robusto) ------------------
    private String buildPrompt(GenerateRequest req) {
        // --- el prompt que ya tenías, reforzado con ejemplos de pesos calculados ---
        double bw = 75.0;
        try {
            if (req != null && req.profile != null && req.profile.weight != null) {
                bw = Double.parseDouble(String.valueOf(req.profile.weight));
            }
        } catch (Exception ignored) {}

        double ejemplo50 = Math.round(bw * 0.50 * 10.0) / 10.0;
        double ejemplo30 = Math.round(bw * 0.30 * 10.0) / 10.0;

        StringBuilder p = new StringBuilder();
        p.append("RESPONDE SÓLO con JSON válido. Sin explicaciones ni texto adicional.\n");
        p.append("IMPORTANTE: el campo \"weightFormula\" debe ser un valor numérico terminado en \" kg\" (por ejemplo \"37.5 kg\") ");
        p.append("o exactamente la cadena \"Peso corporal\" para ejercicios de peso corporal. NUNCA devuelvas expresiones (ej. \"0.5 * bodyWeight\").\n\n");

        p.append("Salida esperada (formato exacto):\n");
        p.append("{ \"weeks\": [ { \"week\": 1, \"days\": [ { \"dayOfWeek\": 1, \"items\": [ { \"exerciseId\": \"press_banca\", \"exerciseName\": \"Press de banca\", \"group\": \"Pecho\", \"sets\": 3, \"reps\": 10, \"weightFormula\": \"" + String.format(Locale.US,"%.1f kg", ejemplo50) + "\" } ] }, { \"dayOfWeek\": 2, \"items\": [ { \"exerciseId\": \"rest\", \"exerciseName\": \"Descanso\", \"group\": \"rest\", \"sets\": 0, \"reps\": \"\", \"weightFormula\": \"\" } ] } ] } ] }\n\n");

        p.append("REGLAS CLAVE (RESPONDER EXACTAMENTE):\n");
        p.append("1) Devuelve SOLO JSON. No uses fences ni texto fuera del JSON.\n");
        p.append("2) weeks -> array; cada week debe contener week (int) y days (array).\n");
        p.append("3) Cada day debe tener dayOfWeek (1..7) y items (array). Si no hay ejercicios en un día, incluye el objeto rest como en el ejemplo.\n");
        p.append("4) Cada item debe tener: exerciseId (string), exerciseName (string), group (string), sets (int), reps (int o empty string), weightFormula (string con ' kg' o 'Peso corporal').\n");
        p.append("5) Para porcentajes o multiplicadores del peso corporal, calcula el número y devuelve con UNA cifra decimal seguida de ' kg'. Ej: para weight=" + String.format(Locale.US,"%.1f kg", bw) + " -> 50% => \"" + String.format(Locale.US,"%.1f kg", ejemplo50) + "\".\n");
        p.append("6) Devuelve exactamente " + (req != null && req.profile != null && req.profile.durationWeeks != null ? req.profile.durationWeeks : 4) + " semanas.\n");
        p.append("7) Máximo 4 ejercicios por día.\n");
        p.append("8) Usa exclusivamente los nombres de ejercicios listados a continuación (exactos):\n");
        p.append("Pecho: Press de banca, Press inclinado con mancuernas, Aperturas con mancuernas, Fondos en paralelas\n");
        p.append("Espalda: Dominadas, Remo con barra, Peso muerto, Jalón al pecho en polea\n");
        p.append("Hombros: Press militar, Elevaciones laterales, Pájaros, Encogimientos\n");
        p.append("Bíceps: Curl con barra, Curl alternado con mancuernas, Curl en banco Scott\n");
        p.append("Tríceps: Fondos en paralelas, Extensión en polea, Press francés\n");
        p.append("Piernas: Sentadilla con barra, Prensa de pierna, Peso muerto rumano, Zancadas, Elevaciones de talones\n");
        p.append("Abdomen/Core: Crunch abdominal, Plancha, Elevaciones de piernas colgado, Rueda abdominal\n\n");

        p.append("USUARIO: ");
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

    // ------------------ extracción y limpieza ------------------
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

    // ------------------ normalización robusta ------------------
    private String normalizeJsonForGenerateResponse(String jsonCandidate, GenerateRequest req) throws Exception {
        JsonNode root = mapper.readTree(jsonCandidate);
        ObjectNode outRoot = mapper.createObjectNode();
        ArrayNode weeksOut = mapper.createArrayNode();

        double bw = 75.0;
        try {
            if (req != null && req.profile != null && req.profile.weight != null) {
                bw = Double.parseDouble(String.valueOf(req.profile.weight));
            }
        } catch (Exception ignored) {}

        ArrayNode weeksIn;
        if (root.has("weeks") && root.get("weeks").isArray()) weeksIn = (ArrayNode) root.get("weeks");
        else if (root.isArray()) weeksIn = (ArrayNode) root;
        else {
            // A veces la respuesta puede venir envuelta en otro campo
            if (root.has("output") && root.get("output").has("weeks")) weeksIn = (ArrayNode) root.at("/output/weeks");
            else throw new RuntimeException("Respuesta AI sin 'weeks'");
        }

        for (int wi = 0; wi < weeksIn.size(); wi++) {
            JsonNode weekNode = weeksIn.get(wi);
            ObjectNode weekOut = mapper.createObjectNode();

            // week number
            if (weekNode.has("week") && weekNode.get("week").canConvertToInt()) weekOut.put("week", weekNode.get("week").intValue());
            else weekOut.put("week", wi + 1);

            // days
            ArrayNode daysOut = mapper.createArrayNode();
            ArrayNode daysIn = null;
            if (weekNode.has("days") && weekNode.get("days").isArray()) daysIn = (ArrayNode) weekNode.get("days");
            else if (weekNode.has("Days") && weekNode.get("Days").isArray()) daysIn = (ArrayNode) weekNode.get("Days");
            else {
                // si no tiene days, intentar inferir un array vacio de 7 dias
                daysIn = mapper.createArrayNode();
            }

            // Si daysIn tiene menos de 7 elementos, permitimos rellenar con rest posteriormente.
            for (int di = 0; di < Math.max(daysIn.size(), 7); di++) {
                JsonNode dayNode = di < daysIn.size() ? daysIn.get(di) : null;
                ObjectNode dayOut = mapper.createObjectNode();

                // dayOfWeek: aceptar número, nombre en inglés o español
                int dow = di + 1;
                if (dayNode != null) {
                    if (dayNode.has("dayOfWeek")) {
                        JsonNode dowNode = dayNode.get("dayOfWeek");
                        dow = parseDayOfWeek(dowNode);
                    } else if (dayNode.has("day")) {
                        dow = parseDayOfWeek(dayNode.get("day"));
                    }
                }
                dayOut.put("dayOfWeek", dow);

                // items: aceptar `items` o `exercises`
                ArrayNode itemsOut = mapper.createArrayNode();
                ArrayNode itemsIn = null;
                if (dayNode != null) {
                    if (dayNode.has("items") && dayNode.get("items").isArray()) itemsIn = (ArrayNode) dayNode.get("items");
                    else if (dayNode.has("exercises") && dayNode.get("exercises").isArray()) itemsIn = (ArrayNode) dayNode.get("exercises");
                    else if (dayNode.has("Exercises") && dayNode.get("Exercises").isArray()) itemsIn = (ArrayNode) dayNode.get("Exercises");
                }
                if (itemsIn == null || itemsIn.size() == 0) {
                    // si no hay items: añadimos un rest placeholder para que el frontend lo maneje igual
                    ObjectNode restItem = mapper.createObjectNode();
                    restItem.put("exerciseId", "rest");
                    restItem.put("exerciseName", "Descanso");
                    restItem.put("group", "rest");
                    restItem.put("sets", 0);
                    restItem.put("reps", "");
                    restItem.put("weightFormula", "");
                    itemsOut.add(restItem);
                } else {
                    for (JsonNode it : itemsIn) {
                        ObjectNode itOut = mapper.createObjectNode();

                        // exerciseId
                        if (it.has("exerciseId") && !it.get("exerciseId").isNull()) {
                            JsonNode idn = it.get("exerciseId");
                            if (idn.isNumber()) itOut.put("exerciseId", idn.asText());
                            else itOut.put("exerciseId", idn.asText());
                        } else if (it.has("id")) itOut.put("exerciseId", it.get("id").asText());
                        else if (it.has("exerciseName")) itOut.put("exerciseId", normalizeToId(it.get("exerciseName").asText()));
                        else itOut.put("exerciseId", "unknown");

                        // exerciseName
                        if (it.has("exerciseName") && !it.get("exerciseName").isNull()) itOut.put("exerciseName", it.get("exerciseName").asText());
                        else if (it.has("name")) itOut.put("exerciseName", it.get("name").asText());
                        else itOut.put("exerciseName", itOut.get("exerciseId").asText());

                        // group (si falta intentar inferir)
                        if (it.has("group") && !it.get("group").isNull()) itOut.put("group", it.get("group").asText());
                        else {
                            String inferred = EX_TO_GROUP.getOrDefault(itOut.get("exerciseName").asText(), "General");
                            itOut.put("group", inferred);
                        }

                        // sets
                        if (it.has("sets") && it.get("sets").canConvertToInt()) itOut.put("sets", it.get("sets").intValue());
                        else itOut.put("sets", it.has("sets") && it.get("sets").isTextual() && !it.get("sets").asText().isBlank() ? tryParseInt(it.get("sets").asText(), 3) : 3);

                        // reps (puede ser int o string vacio)
                        if (it.has("reps")) {
                            if (it.get("reps").isInt()) itOut.put("reps", it.get("reps").intValue());
                            else if (it.get("reps").isTextual()) {
                                String r = it.get("reps").asText();
                                if (r.isBlank()) itOut.put("reps", "");
                                else itOut.put("reps", tryParseInt(r, 10));
                            } else itOut.put("reps", it.get("reps").asText());
                        } else itOut.put("reps", "");

                        // weightFormula: convertir a "NN.N kg" o "Peso corporal"
                        String wf = "";
                        if (it.has("weightFormula") && !it.get("weightFormula").isNull()) wf = it.get("weightFormula").asText().trim();
                        else if (it.has("weight") && !it.get("weight").isNull()) wf = it.get("weight").asText().trim();

                        String wfFixed = fixWeightFormula(wf, bw);
                        itOut.put("weightFormula", wfFixed);

                        // notes (opcional)
                        if (it.has("notes")) itOut.put("notes", it.get("notes").asText(""));
                        else itOut.put("notes", (String) null);

                        itemsOut.add(itOut);
                    }
                }

                dayOut.set("items", itemsOut);
                daysOut.add(dayOut);
            }

            weekOut.set("days", daysOut);
            weeksOut.add(weekOut);
        }

        outRoot.set("weeks", weeksOut);
        return mapper.writeValueAsString(outRoot);
    }

    // ------------------ helpers ------------------
    private int parseDayOfWeek(JsonNode node) {
        if (node == null || node.isNull()) return 1;
        if (node.isInt()) {
            int v = node.intValue();
            if (v >= 1 && v <= 7) return v;
            return Math.max(1, Math.min(7, v));
        }
        String txt = node.asText().trim().toLowerCase();
        // english
        if (txt.startsWith("mon") || txt.contains("monday") || txt.contains("lunes")) return 1;
        if (txt.startsWith("tue") || txt.contains("tuesday") || txt.contains("martes")) return 2;
        if (txt.startsWith("wed") || txt.contains("wednesday") || txt.contains("miercoles") || txt.contains("miércoles")) return 3;
        if (txt.startsWith("thu") || txt.contains("thursday") || txt.contains("jueves")) return 4;
        if (txt.startsWith("fri") || txt.contains("friday") || txt.contains("viernes")) return 5;
        if (txt.startsWith("sat") || txt.contains("saturday") || txt.contains("sabado") || txt.contains("sábado")) return 6;
        if (txt.startsWith("sun") || txt.contains("sunday") || txt.contains("domingo")) return 7;
        // si viene un número como texto
        try {
            int v = Integer.parseInt(txt);
            return Math.max(1, Math.min(7, v));
        } catch (Exception ignored) {}
        return 1;
    }

    private String normalizeToId(String name) {
        if (name == null) return "unknown";
        String s = name.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\s]+", "_");
        s = s.replaceAll("[^a-z0-9_]", "");
        return s;
    }

    private int tryParseInt(String s, int def) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return def; }
    }

    private String fixWeightFormula(String wf, double bw) {
        if (wf == null) return "";
        wf = wf.trim();
        if (wf.isEmpty()) return "";

        // si es "Peso corporal" o variantes, devolver exactamente eso
        String l = wf.toLowerCase(Locale.ROOT);
        if (l.contains("peso corporal") || l.contains("peso_corpor") || l.equals("bodyweight") || l.contains("body weight")) {
            return "Peso corporal";
        }

        // si ya tiene "kg", intentar extraer número y formatear
        Pattern pKg = Pattern.compile("([0-9]+(?:[\\.,][0-9]+)?)\\s*kg", Pattern.CASE_INSENSITIVE);
        Matcher mKg = pKg.matcher(wf);
        if (mKg.find()) {
            String num = mKg.group(1).replace(',', '.');
            double val = Double.parseDouble(num);
            return String.format(Locale.US,"%.1f kg", Math.round(val*10.0)/10.0);
        }

        // si viene como número sin kg -> agregar kg
        Pattern pNum = Pattern.compile("^([0-9]+(?:[\\.,][0-9]+)?)$");
        Matcher mNum = pNum.matcher(wf);
        if (mNum.find()) {
            double val = Double.parseDouble(mNum.group(1).replace(',', '.'));
            return String.format(Locale.US,"%.1f kg", Math.round(val*10.0)/10.0);
        }

        // si viene como porcentaje "50%" -> calcular
        Pattern pPercent = Pattern.compile("([0-9]+(?:[\\.,][0-9]+)?)\\s*%"); // e.g. 50%
        Matcher mPercent = pPercent.matcher(wf);
        if (mPercent.find()) {
            double percent = Double.parseDouble(mPercent.group(1).replace(',', '.'));
            double val = bw * (percent / 100.0);
            return String.format(Locale.US,"%.1f kg", Math.round(val*10.0)/10.0);
        }

        // si viene como multiplicador "0.5 * bodyWeight" o "0.5*bodyWeight"
        Pattern pMul = Pattern.compile("([0-9]+(?:[\\.,][0-9]+)?)\\s*\\*?\\s*(?:bodyweight|body_weight|peso|peso corporal|body weight)", Pattern.CASE_INSENSITIVE);
        Matcher mMul = pMul.matcher(wf);
        if (mMul.find()) {
            double mult = Double.parseDouble(mMul.group(1).replace(',', '.'));
            double val = bw * mult;
            return String.format(Locale.US,"%.1f kg", Math.round(val*10.0)/10.0);
        }

        // fallback: devolver tal cual si parece número con coma/punto
        return wf;
    }
}
