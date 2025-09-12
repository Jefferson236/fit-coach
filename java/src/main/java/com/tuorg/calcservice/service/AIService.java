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

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AIService — llama a Gemini y extrae el JSON producido por la IA de forma robusta.
 */
@Service
public class AIService {

    private final Logger log = LoggerFactory.getLogger(AIService.class);

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${gemini.api.key:}")
    private String geminiKey;

    private final RoutineGeneratorService fallbackGenerator;

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    public AIService(RoutineGeneratorService fallbackGenerator) {
        this.fallbackGenerator = fallbackGenerator;
    }

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
            log.debug("Enviando petición a Gemini (prompt len={}):\n{}", prompt.length(), prompt);
            ResponseEntity<String> resp = rest.exchange(urlWithKey, HttpMethod.POST, entity, String.class);

            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.error("Gemini no respondió 2xx: {} - body: {}", resp.getStatusCode(), resp.getBody());
                throw new RuntimeException("Gemini returned non-2xx: " + resp.getStatusCode());
            }

            String respBody = resp.getBody();
            log.debug("Respuesta cruda de Gemini ({} chars)", respBody == null ? 0 : respBody.length());

            if (respBody == null || respBody.isBlank()) {
                log.error("Gemini devolvió body vacío");
                throw new RuntimeException("Gemini returned empty body");
            }

            // Extraer el JSON contenido dentro de la respuesta de Gemini
            String jsonCandidate = extractJsonFromGeminiResponse(respBody);
            log.debug("JSON candidato extraído ({} chars)", jsonCandidate == null ? 0 : jsonCandidate.length());

            // Parsear a GenerateResponse
            GenerateResponse gr = mapper.readValue(jsonCandidate, GenerateResponse.class);

            if (gr.weeks == null || gr.weeks.isEmpty()) {
                log.warn("Gemini devolvió estructura vacía o sin semanas, usando fallback local");
                return fallbackGenerator.generate(req);
            }

            return gr;

        } catch (RestClientException rce) {
            log.error("Error de conexión al llamar Gemini: {}", rce.toString(), rce);
            return fallbackGenerator.generate(req);
        } catch (Exception ex) {
            log.error("Error procesando respuesta de Gemini: {}", ex.toString(), ex);
            return fallbackGenerator.generate(req);
        }
    }

    /**
     * Construye el prompt — incluye la lista exacta de ejercicios (ya lo tenías).
     */
    private String buildPrompt(GenerateRequest req) throws Exception {
        StringBuilder p = new StringBuilder();

        p.append("INSTRUCCIONES IMPORTANTES (RESPONDE SOLO JSON VÁLIDO):\n");
        p.append("Devuelve ÚNICAMENTE JSON válido (sin texto adicional) en el esquema Java GenerateResponse:\n");
        p.append("{ \"title\": \"opcional\", \"weeks\": [ { \"week\": number, \"days\": [ { \"dayOfWeek\": number, \"items\": [ { \"exerciseId\": string_or_number, \"exerciseName\": string, \"group\": string, \"sets\": number, \"reps\": string, \"weightFormula\": string, \"notes\": string } ] } ] } ] }\n\n");

        p.append("REGLAS:\n");
        p.append("1) Usa **solo** los ejercicios listados más abajo (mismos nombres exactos, en español). No inventes otros.\n");
        p.append("2) Genera exactamente durationWeeks semanas.\n");
        p.append("3) Días por semana según split: fullbody=3, upper-lower=4, push-pull-legs=3, default=3.\n");
        p.append("4) Ajusta sets/reps según goal (fuerza/resistencia/hipertrofia) y level (beginner/intermediate/advanced).\n");
        p.append("5) Cada item debe tener: exerciseId (slug o id), exerciseName EXACTO (uno de la lista), group (uno de los grupos listados), sets, reps (ej. '3-5' o '8-12'), weightFormula (ej. '0.6*1RM' o 'Peso corporal'), notes opcional.\n");
        p.append("6) No incluyas explicaciones ni texto fuera del JSON.\n\n");

        p.append("EJERCICIOS PERMITIDOS (usa exactamente estos nombres y estos grupos):\n");
        p.append("{\n");
        p.append("  \"Pecho\": [\"Press de banca\",\"Press inclinado con mancuernas\",\"Aperturas con mancuernas\",\"Fondos en paralelas\"],\n");
        p.append("  \"Espalda\": [\"Dominadas\",\"Remo con barra\",\"Peso muerto\",\"Jalón al pecho en polea\"],\n");
        p.append("  \"Hombros\": [\"Press militar\",\"Elevaciones laterales\",\"Pájaros (elevaciones posteriores)\",\"Encogimientos (shrugs)\"],\n");
        p.append("  \"Bíceps\": [\"Curl con barra\",\"Curl alternado con mancuernas\",\"Curl en banco Scott\"],\n");
        p.append("  \"Tríceps\": [\"Fondos en paralelas\",\"Extensión en polea\",\"Press francés\"],\n");
        p.append("  \"Piernas\": [\"Sentadilla con barra\",\"Prensa de pierna\",\"Peso muerto rumano\",\"Zancadas (lunges)\",\"Elevaciones de talones\"],\n");
        p.append("  \"Abdomen/Core\": [\"Crunch abdominal\",\"Plancha\",\"Elevaciones de piernas colgado\",\"Rueda abdominal\"]\n");
        p.append("}\n\n");

        p.append("NOTAS:\n");
        p.append("- Para exerciseId puedes usar un slug (ej. 'press-de-banca') o un número. Lo importante es que exerciseName sea exactamente uno de los nombres anteriores.\n");
        p.append("- Ejemplo de reps: '3-5' (fuerza), '8-12' (hipertrofia), '15-20' (resistencia).\n\n");

        p.append("Perfil del usuario:\n");
        if (req != null && req.profile != null) {
            p.append(mapper.writeValueAsString(req.profile)).append("\n\n");
        } else {
            p.append("{\"note\":\"perfil no proporcionado\"}\n\n");
        }

        p.append("DEVUELVE SOLO JSON VÁLIDO.\n");

        return p.toString();
    }

    /**
     * Extrae por heurística el bloque JSON que genera Gemini.
     * - Busca recursivamente nodos textuales dentro del JSON wrapper (candidates, outputs, etc.)
     * - Si encuentra texto, intenta aislar el primer bloque JSON balanceado.
     * - Si no encuentra nada, intenta extraer primer bloque JSON de la respuesta cruda.
     */
    private String extractJsonFromGeminiResponse(String respBody) throws Exception {
        if (respBody == null) throw new RuntimeException("Respuesta vacía");

        // 1) parsear como JSON y buscar text nodes recursivamente
        try {
            JsonNode root = mapper.readTree(respBody);
            String found = findJsonTextInNode(root);
            if (found != null) {
                String trimmed = trimToJson(found);
                if (trimmed != null) return trimmed;
                if (looksLikeJson(found)) return found;
            }
        } catch (Exception e) {
            // no es un JSON válido a nivel top-level o fallo; seguimos con heurística de texto bruto
            log.debug("No pude parsear respuesta wrapper como JSON o no encontré texto allí: {}", e.toString());
        }

        // 2) heurística directa sobre el cuerpo crudo: buscar primer bloque JSON balanceado
        String candidate = trimToJson(respBody);
        if (candidate != null) return candidate;

        // 3) si aún no, intentar extraer mediante regex simple (menos fiable) - aquí solo devolver null para fallback
        throw new RuntimeException("No pude extraer JSON válido de la respuesta de Gemini");
    }

    /**
     * Busca recursivamente cualquier nodo textual y prueba si contiene JSON o un bloque JSON.
     * Devuelve el primer texto interesante (por ejemplo el contenido de candidates[*].content.parts[*].text)
     */
    private String findJsonTextInNode(JsonNode node) {
        if (node == null) return null;

        if (node.isTextual()) {
            String txt = node.asText();
            // prefiero devolver textos bastante largos (posible JSON) o que parezcan JSON
            if (txt.length() > 20) {
                // si ya parece JSON lo devolvemos
                if (looksLikeJson(txt)) return txt;
                // si contiene una "{" dentro, puede contener JSON incrustado
                if (txt.indexOf('{') >= 0) return txt;
            }
            return null;
        }

        if (node.isContainerNode()) {
            // iterar campos
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    JsonNode found = it.next().getValue();
                    String r = findJsonTextInNode(found);
                    if (r != null) return r;
                }
            } else if (node.isArray()) {
                for (JsonNode el : node) {
                    String r = findJsonTextInNode(el);
                    if (r != null) return r;
                }
            }
        }

        return null;
    }

    private boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    /**
     * Extrae el primer bloque JSON balanceado empezando por la primera llave '{'
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
