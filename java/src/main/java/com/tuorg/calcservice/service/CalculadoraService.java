package com.tuorg.calcservice.service;

public class CalculadoraService {
public double oneRepMax(double peso, int reps) {
    if(reps <= 1) return peso;
    return peso * (1 + reps / 30.0);
  }
  public double percentOf1RM(double oneRm, double percent) {
    return oneRm * percent;
  }
}
