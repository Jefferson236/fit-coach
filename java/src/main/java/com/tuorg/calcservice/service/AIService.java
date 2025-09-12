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

    @Value("${gemini.api.key}")
    private String geminiKey;

    // URL (modelo gemini-2.0-flash tal como indicaste)
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    /**
     * Genera una rutina llamando a Gemini. Devuelve un GenerateResponse (o lanza excepción si falla).
     */
    public GenerateResponse generateRoutineFromAI(GenerateRequest req) throws Exception {
        // Construir prompt pidiéndole JSON EXACTO con el formato de GenerateResponse
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
        // Se incluye X-goog-api-key tal como exige el ejemplo curl
        headers.set("X-goog-api-key", this.geminiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        // Llamamos a Gemini (con query param ?key=... se incluye por redundancia)
        String urlWithKey = GEMINI_URL + "?key=" + this.geminiKey;

        ResponseEntity<String> resp = rest.exchange(urlWithKey, HttpMethod.POST, entity, String.class);

        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Gemini request failed: " + resp.getStatusCode() + " - " + resp.getBody());
        }

        String respBody = resp.getBody();
        if (respBody == null || respBody.isBlank()) {
            throw new RuntimeException("Gemini returned empty body");
        }

        // Intentamos extraer el JSON que generó la IA.
        String jsonCandidate = extractJsonFromGeminiResponse(respBody);

        // Parsear a GenerateResponse
        GenerateResponse gr = mapper.readValue(jsonCandidate, GenerateResponse.class);

        // (Opcional) valida mínimamente que no esté vacío
        if (gr.weeks == null) {
            throw new RuntimeException("AI returned invalid routine (no weeks)");
        }

        return gr;
    }

    private String buildPrompt(GenerateRequest req) throws Exception {
        /*
         * Prompt estricto en español que obliga a la IA a devolver SOLO JSON con la estructura
         * que espera el frontend: GenerateResponse { title?, weeks[] { week, days[] { dayOfWeek, items[] { exerciseId, exerciseName, group, sets, reps, weightFormula, notes? } } } }
         *
         * Se incluye el perfil en JSON para condicionar la generación.
         */
        StringBuilder p = new StringBuilder();

        // Introducción y esquema requerido
        p.append("IMPORTANTE: devuelve SOLO JSON válido, sin ninguna explicación, sin texto adicional, ");
        p.append("sin comillas extra, ni encabezados. El JSON debe ajustarse exactamente al esquema pedido.\n\n");

        p.append("SCHEMA (debe respetarse exactamente):\n");
        p.append("{\n");
        p.append("  \"title\": \"string opcional\",\n");
        p.append("  \"weeks\": [\n");
        p.append("    {\n");
        p.append("      \"week\": <numero>,\n");
        p.append("      \"days\": [\n");
        p.append("        {\n");
        p.append("          \"dayOfWeek\": <numero 1..7>,\n");
        p.append("          \"items\": [\n");
        p.append("            {\n");
        p.append("              \"exerciseId\": <string_o_numero_unico>,\n");
        p.append("              \"exerciseName\": \"<nombre de ejercicio en español>\",\n");
        p.append("              \"group\": \"<Pierna|Pecho|Espalda|Hombro|Bíceps|Tríceps|Core|Cardio|General>\",\n");
        p.append("              \"sets\": <entero>,\n");
        p.append("              \"reps\": \"<rango o numero, e.g. '8-12' o '10'>\",\n");
        p.append("              \"weightFormula\": \"<cadena corta, e.g. '0.6*1RM' o 'Peso corporal'>\",\n");
        p.append("              \"notes\": \"<opcional, max 60 caracteres>\"\n");
        p.append("            }\n");
        p.append("          ]\n");
        p.append("        }\n");
        p.append("      ]\n");
        p.append("    }\n");
        p.append("  ]\n");
        p.append("}\n\n");

        // Explicar claramente cómo condicionar la rutina según perfil
        p.append("INSTRUCCIONES para generar la rutina (usa el perfil suministrado más abajo):\n");
        p.append("1) Genera exactamente 'durationWeeks' semanas.\n");
        p.append("2) Determina días por semana según 'split': fullbody -> 3, upper-lower -> 4, push-pull-legs -> 3. Si split desconocido -> 3.\n");
        p.append("3) Para cada día produce entre 3 y 6 items (ejercicios) coherentes con 'goal' y 'level':\n");
        p.append("   - 'fuerza': prioriza ejercicios compuestos (sentadilla, press banca, peso muerto, remo), sets = 4-6, reps = '3-6', weightFormula '0.8-0.9*1RM' o '0.85*1RM'.\n");
        p.append("   - 'hipertrofia': prioriza combinación de compuestos y accesorios, sets = 3-4, reps = '8-12', weightFormula '0.6*1RM'.\n");
        p.append("   - 'resistencia': prioriza ejercicios de mayor rep o cardio, sets = 2-4, reps = '12-20' o '15-30', weightFormula 'ligero' o 'peso corporal'.\n");
        p.append("4) Usa nombres de ejercicios en español (ej.: 'Sentadilla', 'Press Banca', 'Remo con barra', 'Fondos', 'Curl con barra', 'Plancha').\n");
        p.append("5) Cada item debe contener 'exerciseId' (slug o id único), 'exerciseName', 'group' (uno de los grupos permitidos), 'sets' (int), 'reps' (string o int), 'weightFormula' (string), 'notes' opcional.\n");
        p.append("6) 'dayOfWeek' puede ser 1..7 para identificar el día en la semana (no importa si repites números entre semanas). Sea coherente.\n");
        p.append("7) No incluyas listas de explicación ni pasos. SOLO el JSON de salida.\n");
        p.append("8) Notas deben ser breves (<=60 caracteres) o cadena vacía.\n\n");

        // Añadir perfil JSON para que la IA lo tenga como contexto inequívoco
        p.append("Perfil de entrada (JSON):\n");
        if (req != null && req.profile != null) {
            String profileJson = mapper.writeValueAsString(req.profile);
            p.append(profileJson).append("\n\n");
        } else {
            p.append("{\"note\":\"perfil no proporcionado, usa valores sensatos por defecto\"}\n\n");
        }

        // Ejemplo mínimo (no devolverlo; solo como guía para el modelo)
        p.append("Ejemplo de salida (solo para referencia, NO añadir texto antes/depues, devuelve SOLO JSON igual o similar al esquema):\n");
        p.append("{\n");
        p.append("  \"title\": \"Rutina ejemplo\",\n");
        p.append("  \"weeks\": [\n");
        p.append("    {\n");
        p.append("      \"week\": 1,\n");
        p.append("      \"days\": [\n");
        p.append("        {\n");
        p.append("          \"dayOfWeek\": 1,\n");
        p.append("          \"items\": [\n");
        p.append("            {\n");
        p.append("              \"exerciseId\": \"sentadilla\",\n");
        p.append("              \"exerciseName\": \"Sentadilla\",\n");
        p.append("              \"group\": \"Pierna\",\n");
        p.append("              \"sets\": 3,\n");
        p.append("              \"reps\": \"8-12\",\n");
        p.append("              \"weightFormula\": \"0.6*1RM\",\n");
        p.append("              \"notes\": \"\"\n");
        p.append("            }\n");
        p.append("          ]\n");
        p.append("        }\n");
        p.append("      ]\n");
        p.append("    }\n");
        p.append("  ]\n");
        p.append("}\n\n");

        p.append("Recuerda: DEVUELVE SOLO JSON VÁLIDO y nada más. Si devuelves texto adicional se considerará error.\n");

        return p.toString();
    }

    /**
     * Extrae el texto JSON útil de la respuesta de Gemini. La API puede devolver estructura
     * con 'candidates' u otra anidación: intentamos detectar el primer bloque JSON válido.
     */
    private String extractJsonFromGeminiResponse(String respBody) throws Exception {
        // 1) Intentar parsear como JSON y buscar claves usuales
        JsonNode root = mapper.readTree(respBody);

        // Buscamos posibles nodos donde Gemini ponga la salida textual
        // - candidates[*].content or candidates[*].output or candidates[*].messages etc.
        List<String> possiblePaths = List.of(
                "/candidates/0/content/parts/0/text",
                "/candidates/0/output/0/content/parts/0/text",
                "/candidates/0/content/0/parts/0/text",
                "/candidates/0/parts/0/text",
                "/outputs/0/text",
                "/candidates/0/text",
                "/response",
                "/items/0/text"
        );

        for (String p : possiblePaths) {
            try {
                JsonNode node = root.at(p);
                if (!node.isMissingNode() && node.isTextual()) {
                    String txt = node.asText();
                    String json = trimToJson(txt);
                    if (json != null) return json;
                    // si txt ya parece JSON
                    if (looksLikeJson(txt)) return txt;
                }
            } catch (Exception ignored) {}
        }

        // 2) Si no encontramos por caminos, buscar el primer fragmento entre '{' y '}' que parsee:
        String candidate = trimToJson(respBody);
        if (candidate != null) return candidate;

        // 3) fallback: si la respuesta entera es JSON que representa la estructura
        if (looksLikeJson(respBody)) return respBody;

        throw new RuntimeException("No pude extraer JSON válido de la respuesta de Gemini. Response: " + respBody);
    }

    private boolean looksLikeJson(String s) {
        String t = s == null ? "" : s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    /**
     * Extrae por heurística el primer bloque JSON (desde primera llave '{' hasta la llave de cierre final).
     * Intenta balancear llaves para encontrar el final correcto.
     */
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
