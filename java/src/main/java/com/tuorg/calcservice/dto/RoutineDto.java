package com.tuorg.calcservice.dto;

import java.util.List;

public class RoutineDto {
    public String routineId;
    public Long userId;
    public String name;
    public String source;
    public long createdAt;
    public long startDateEpoch;
    public long endDateEpoch;

    public ProfileDto profile;
    public Integer durationWeeks;
    public String split;
    public String goal;
    public List<GenerateResponse.WeekDto> weeks;
    public Integer totalDays;
    public Integer totalExercises;
    public String notes;
    public RoutineDto() {}
}

