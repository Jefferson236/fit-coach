package com.tuorg.calcservice.service;

import org.springframework.stereotype.Service;

@Service
public class CalculadoraServiceImpl implements CalculadoraService {

    /**
     * Epley formula: 1RM ≈ weight * (1 + reps/30)
     * (suficiente como stub; reemplaza con la fórmula que prefieras)
     */
    @Override
    public double estimateOneRepMax(double weight, int reps) {
        if (reps <= 0) return weight;
        return weight * (1.0 + reps / 30.0);
    }

    @Override
    public double weightFromPercentage(double oneRepMax, double percentage) {
        if (Double.isNaN(oneRepMax) || percentage <= 0) return 0;
        return oneRepMax * percentage;
    }
}
