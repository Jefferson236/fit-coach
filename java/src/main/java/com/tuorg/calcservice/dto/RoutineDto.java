package com.tuorg.calcservice.dto;

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
}
