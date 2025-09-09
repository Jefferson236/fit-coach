package com.tuorg.calcservice.dto;

import java.util.List;

public class GenerateRequest {
    public ProfileDto profile;
    public List<ExerciseDto> exercises;
    public GenerateOptions options;
    public static class GenerateOptions {
        public Integer maxPerDay;
        public Boolean preferCompounds;
    }
}

