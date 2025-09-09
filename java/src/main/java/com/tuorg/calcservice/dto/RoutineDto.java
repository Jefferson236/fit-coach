package com.tuorg.calcservice.service;

<<<<<<< HEAD
import com.tuorg.calcservice.dto.GenerateRequest;
import com.tuorg.calcservice.dto.GenerateResponse;
import com.tuorg.calcservice.dto.ExerciseDto;
import com.tuorg.calcservice.dto.GenerateResponse.WeekDto;
import com.tuorg.calcservice.dto.GenerateResponse.DayDto;
import com.tuorg.calcservice.dto.GenerateResponse.ItemDto;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoutineGeneratorService {

  private final CalculadoraService calc;

  public RoutineGeneratorService(CalculadoraService calc) {
    this.calc = calc;
  }

  public GenerateResponse generate(GenerateRequest req) {

    if(req.profile == null) throw new IllegalArgumentException("Profile required");
    int weeks = req.profile.durationWeeks == null ? 4 : req.profile.durationWeeks;
    String split = req.profile.split == null ? "fullbody" : req.profile.split;
    String goal = req.profile.goal == null ? "hipertrofia" : req.profile.goal;
    int daysPerWeek = determineDaysPerWeek(split);


    Map<String, List<ExerciseDto>> byGroup = new HashMap<>();
    if(req.exercises != null) {
      for(ExerciseDto e : req.exercises) {
        byGroup.computeIfAbsent(e.group == null ? "General" : e.group, k -> new ArrayList<>()).add(e);
      }
    }

    GenerateResponse resp = new GenerateResponse();
    resp.weeks = new ArrayList<>();

    for(int w=1; w<=weeks; w++){
      WeekDto week = new WeekDto();
      week.week = w;
      week.days = new ArrayList<>();
      for(int d=1; d<=daysPerWeek; d++){
        DayDto day = new DayDto();
        day.dayOfWeek = d;
        day.items = new ArrayList<>();

        List<ExerciseDto> candidates = pickExercisesForDay(byGroup, d, req.options != null ? req.options.maxPerDay : 5);
        for(ExerciseDto e : candidates) {
          ItemDto item = new ItemDto();
          item.exerciseId = e.id;
          item.exerciseName = e.name;

          if("fuerza".equalsIgnoreCase(goal)) {
            item.sets = 5;
            item.reps = "3-5";
            item.weightFormula = "0.85*1RM";
          } else if("resistencia".equalsIgnoreCase(goal)) {
            item.sets = 3;
            item.reps = "15-20";
            item.weightFormula = "bodyweight or light";
          } else {
            item.sets = 3;
            item.reps = "8-12";
            item.weightFormula = "0.6*1RM";
          }
          day.items.add(item);
        }
        week.days.add(day);
      }
      resp.weeks.add(week);
    }

    return resp;
  }

  private int determineDaysPerWeek(String split) {
    if(split == null) return 3;
    switch(split.toLowerCase()) {
      case "fullbody": return 3;
      case "upper-lower": return 4;
      case "push-pull-legs": return 3;
      default: return 3;
    }
  }

  private List<ExerciseDto> pickExercisesForDay(Map<String, List<ExerciseDto>> byGroup, int dayIndex, Integer maxPerDay) {
    int limit = (maxPerDay == null) ? 5 : Math.max(3, maxPerDay);

    List<ExerciseDto> all = byGroup.values().stream().flatMap(List::stream).collect(Collectors.toList());
    if(all.isEmpty()) return Collections.emptyList();
    List<ExerciseDto> out = new ArrayList<>();
    for(int i=0; i<limit; i++){
      out.add(all.get((dayIndex + i) % all.size()));
    }
    return out;
  }
=======
public class RoutineDto {
    public String routineId;
    public List<WeekDto> weeks;

    public static class WeekDto {
        public int week;
        public List<DayDto> days;
    }

    public static class DayDto {
        public int dayOfWeek;
        public List<ItemDto> items;
    }

    public static class ItemDto {
        public Long exerciseId;
        public String exerciseName;
        public int sets;
        public String reps;
        public String weightFormula;
        public String notes;
    }
>>>>>>> 5a5b5cb1d7626faf7b620491c8aa496d5a3d416c
}

