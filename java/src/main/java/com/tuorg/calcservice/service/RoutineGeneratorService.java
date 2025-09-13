package com.tuorg.calcservice.service;

import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import com.tuorg.calcservice.dto.ExerciseDto;
import com.tuorg.calcservice.dto.GenerateResponse.WeekDto;
import com.tuorg.calcservice.dto.GenerateResponse.DayDto;
import com.tuorg.calcservice.dto.GenerateResponse.ItemDto;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RoutineGeneratorService {

  private final CalculadoraService calc;

  public RoutineGeneratorService(CalculadoraService calc) {
    this.calc = calc;
  }

  public GenerateResponse generate(GenerateRequest req) {
    if (req.profile == null) throw new IllegalArgumentException("Se requiere un perfil");

    int weeks = req.profile.durationWeeks == null ? 4 : req.profile.durationWeeks;
    String split = req.profile.split == null ? "Fullbody" : req.profile.split;
    String goal = req.profile.goal == null ? "Hipertrofia" : req.profile.goal;
    int daysPerWeek = determineDaysPerWeek(split);
    Double bodyWeight = req.profile.weight; // lo usamos para calcular el peso real

    // Agrupar ejercicios
    Map<String, List<ExerciseDto>> byGroup = new HashMap<>();
    if (req.exercises != null) {
      for (ExerciseDto e : req.exercises) {
        byGroup.computeIfAbsent(
                e.group == null ? "General" : e.group,
                k -> new ArrayList<>()
        ).add(e);
      }
    }

    GenerateResponse resp = new GenerateResponse();
    resp.weeks = new ArrayList<>();

    // Generar semanas y días
    for (int w = 1; w <= weeks; w++) {
      WeekDto week = new WeekDto();
      week.week = w;
      week.days = new ArrayList<>();

      for (int d = 1; d <= daysPerWeek; d++) {
        DayDto day = new DayDto();
        day.dayOfWeek = d;
        day.items = new ArrayList<>();

        List<ExerciseDto> candidates =
                pickExercisesForDay(byGroup, d, req.options != null ? req.options.maxPerDay : 5);

        for (ExerciseDto e : candidates) {
          ItemDto item = new ItemDto();
          item.exerciseId = e.id;
          item.exerciseName = e.name;

          // sets y reps desde CalculadoraService
          item.sets = calc.recommendedSets(goal);
          item.reps = calc.recommendedReps(goal);

          // fórmula inicial
          String rawFormula = calc.recommendedWeight(goal, e.name);
          // la convertimos a texto listo para mostrar
          item.weightFormula = toDisplayWeight(rawFormula, bodyWeight, e.name);

          day.items.add(item);
        }

        // 🔹 Si no hubo ejercicios → marcar "Descanso"
        if (day.items.isEmpty()) {
          ItemDto rest = new ItemDto();
          rest.exerciseId = "rest";
          rest.exerciseName = "Descanso";
          rest.group = "rest";
          rest.sets = 0;
          rest.reps = "";
          rest.weightFormula = "";
          day.items.add(rest);
        }

        week.days.add(day);
      }
      resp.weeks.add(week);
    }

    return resp;
  }

  private int determineDaysPerWeek(String split) {
    if (split == null) return 3;
    switch (split.toLowerCase()) {
      case "fullbody":
        return 3;
      case "upper-lower":
        return 4;
      case "push-pull-legs":
        return 3;
      default:
        return 3;
    }
  }

  private List<ExerciseDto> pickExercisesForDay(Map<String, List<ExerciseDto>> byGroup, int dayIndex, Integer maxPerDay) {
    int limit = (maxPerDay == null) ? 5 : Math.max(3, maxPerDay);

    List<ExerciseDto> all = byGroup.values().stream().flatMap(List::stream).collect(Collectors.toList());
    if (all.isEmpty()) return Collections.emptyList();

    List<ExerciseDto> out = new ArrayList<>();
    for (int i = 0; i < limit; i++) {
      out.add(all.get((dayIndex + i) % all.size()));
    }
    return out;
  }

  // ======================
  // Helpers de peso
  // ======================

  private static final Pattern BODY_WEIGHT_EXPR =
          Pattern.compile("^\\s*([0-9]*\\.?[0-9]+)\\s*\\*\\s*bodyWeight\\s*$", Pattern.CASE_INSENSITIVE);

  private String toDisplayWeight(String weightFormula, Double bodyWeight, String exerciseName) {
    if (bodyWeight == null) return "—";

    if (weightFormula != null && !weightFormula.isBlank()) {
      Matcher m = BODY_WEIGHT_EXPR.matcher(weightFormula);
      if (m.find()) {
        double mult = Double.parseDouble(m.group(1));
        double kg = mult * bodyWeight;
        return String.format(Locale.US, "%.1f kg", kg);
      }
      if (weightFormula.trim().equals("0")) {
        return isBodyweight(exerciseName) ? "Peso corporal" : "0 kg";
      }
      return weightFormula; // fallback
    }

    // fallback por nombre
    Double mult = defaultMultiplierFor(exerciseName);
    if (mult == null) {
      return isBodyweight(exerciseName) ? "Peso corporal" : "—";
    }
    if (mult == 0.0) {
      return isBodyweight(exerciseName) ? "Peso corporal" : "0 kg";
    }
    double kg = mult * bodyWeight;
    return String.format(Locale.US, "%.1f kg", kg);
  }

  private boolean isBodyweight(String name) {
    if (name == null) return false;
    String n = name.toLowerCase(Locale.ROOT);
    return n.contains("dominada") || n.contains("pull-up") ||
            n.contains("flexión")  || n.contains("push-up") ||
            n.contains("plancha")  || n.contains("crunch")  ||
            n.contains("abdominal")|| n.contains("elevaciones de piernas") ||
            n.contains("hanging leg raise");
  }

  private Double defaultMultiplierFor(String name) {
    if (name == null) return null;
    String n = name.toLowerCase(Locale.ROOT);

    if (n.contains("press de banca")) return 0.5;
    if (n.contains("remo con barra")) return 0.4;
    if (n.contains("sentadilla"))     return 0.5;
    if (n.contains("crunch"))         return 0.0;
    if (n.contains("press inclinado"))return 0.4;
    if (n.contains("jalón al pecho")) return 0.4;
    if (n.contains("prensa de pierna"))return 0.6;
    if (n.contains("plancha"))        return 0.0;
    if (n.contains("aperturas"))      return 0.3;
    if (n.contains("dominadas"))      return 0.0;
    if (n.contains("peso muerto rumano")) return 0.4;
    if (n.contains("elevaciones de piernas")) return 0.0;

    return null;
  }
}
