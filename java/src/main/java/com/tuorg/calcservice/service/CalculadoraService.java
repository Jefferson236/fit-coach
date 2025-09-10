package com.tuorg.calcservice.service;

public interface CalculadoraService {
    /**
     * Estima 1RM (one-rep max) usando una fórmula simple (Epley).
     * @param weight peso levantado
     * @param reps repeticiones realizadas
     * @return estimación de 1RM
     */
    double estimateOneRepMax(double weight, int reps);

    /**
     * Calcula el peso objetivo dado un porcentaje del 1RM (por ejemplo 0.75).
     */
    double weightFromPercentage(double oneRepMax, double percentage);
}

