package com.tuorg.calcservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerateResponse {

  // Campos opcionales que puedas necesitar (title, message, etc.)
  private String routineId;
  private String title;
  private String message;

  // Principal: semanas
  private List<WeekDto> weeks;

  // getters / setters
  public String getRoutineId() { return routineId; }
  public void setRoutineId(String routineId) { this.routineId = routineId; }

  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }

  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }

  public List<WeekDto> getWeeks() { return weeks; }
  public void setWeeks(List<WeekDto> weeks) { this.weeks = weeks; }

  // ================= Nested DTOs =================

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class WeekDto {
    private Integer week;
    private List<DayDto> days;

    public Integer getWeek() { return week; }
    public void setWeek(Integer week) { this.week = week; }

    public List<DayDto> getDays() { return days; }
    public void setDays(List<DayDto> days) { this.days = days; }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DayDto {
    private Integer dayOfWeek;
    private List<ItemDto> items;

    public Integer getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Integer dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public List<ItemDto> getItems() { return items; }
    public void setItems(List<ItemDto> items) { this.items = items; }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ItemDto {
    // exerciseId puede llegar como number o string -> lo guardamos como String
    private String exerciseId;
    private String exerciseName;

    // La IA devuelve a veces "group" — lo añadimos
    private String group;

    // sets puede venir como número o string -> normalizamos a Integer
    private Integer sets;

    // reps lo guardamos como String porque puede ser "8-12", "hasta el fallo", etc.
    private String reps;

    private String weightFormula;
    private String notes;

    public String getExerciseId() { return exerciseId; }
    public String getExerciseName() { return exerciseName; }
    public String getGroup() { return group; }
    public Integer getSets() { return sets; }
    public String getReps() { return reps; }
    public String getWeightFormula() { return weightFormula; }
    public String getNotes() { return notes; }

    // JSON setters: tolerantes a tipos mixtos

    @JsonSetter("exerciseId")
    public void setExerciseId(JsonNode node) {
      if (node == null || node.isNull()) {
        this.exerciseId = null;
      } else if (node.isTextual()) {
        this.exerciseId = node.asText();
      } else if (node.isNumber()) {
        this.exerciseId = node.asText();
      } else {
        this.exerciseId = node.toString();
      }
    }

    @JsonProperty("exerciseName")
    public void setExerciseName(String exerciseName) { this.exerciseName = exerciseName; }

    @JsonProperty("group")
    public void setGroup(String group) { this.group = group; }

    @JsonSetter("sets")
    public void setSets(JsonNode node) {
      if (node == null || node.isNull()) { this.sets = null; return; }
      try {
        if (node.isInt() || node.isLong()) this.sets = node.asInt();
        else if (node.isTextual()) {
          String t = node.asText().trim();
          if (t.isEmpty()) this.sets = null;
          else this.sets = Integer.parseInt(t.replaceAll("[^0-9]", ""));
        } else if (node.isNumber()) this.sets = node.asInt();
        else this.sets = null;
      } catch (Exception e) {
        this.sets = null;
      }
    }

    @JsonSetter("reps")
    public void setReps(JsonNode node) {
      if (node == null || node.isNull()) { this.reps = null; return; }
      if (node.isTextual()) this.reps = node.asText();
      else if (node.isNumber()) this.reps = node.asText();
      else this.reps = node.toString();
    }

    @JsonProperty("weightFormula")
    public void setWeightFormula(String weightFormula) { this.weightFormula = weightFormula; }

    @JsonProperty("notes")
    public void setNotes(String notes) { this.notes = notes; }
  }
}
