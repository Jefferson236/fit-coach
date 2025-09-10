package com.tuorg.calcservice.service;

import org.springframework.stereotype.Service;

@Service
public class CalculadoraServiceImpl implements CalculadoraService {

    @Override
    public int recommendedSets(String goal) {
        if ("fuerza".equalsIgnoreCase(goal)) return 5;
        if ("resistencia".equalsIgnoreCase(goal)) return 3;
        return 3; // hipertrofia por defecto
    }

    @Override
    public String recommendedReps(String goal) {
        if ("fuerza".equalsIgnoreCase(goal)) return "3-5";
        if ("resistencia".equalsIgnoreCase(goal)) return "15-20";
        return "8-12"; // hipertrofia
    }

    @Override
    public String recommendedWeight(String goal, String exerciseName) {
        if ("fuerza".equalsIgnoreCase(goal)) {
            return "≈85% de 1RM";
        } else if ("resistencia".equalsIgnoreCase(goal)) {
            return "Peso corporal o ligero";
        } else {
            return "≈60% de 1RM";
        }
    }
}
