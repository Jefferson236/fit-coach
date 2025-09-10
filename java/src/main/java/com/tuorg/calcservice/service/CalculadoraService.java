package com.tuorg.calcservice.service;

public interface CalculadoraService {
    int recommendedSets(String goal);
    String recommendedReps(String goal);
    String recommendedWeight(String goal, String exerciseName);
}
