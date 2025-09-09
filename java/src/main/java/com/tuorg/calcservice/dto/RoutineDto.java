package com.tuorg.calcservice.dto;

import java.time.Instant;
import java.util.List;

public class RoutineDto {
    // Identificadores / metadata
    public String routineId;           // UUID o null (WP puede asignar otro id)
    public Long userId;                // opcional: id del usuario si aplica
    public String name;                // nombre de la rutina (p. ej. "Rutina PPL 6 sem")
    public String source;              // "generator" | "manual" | "import"
    public long createdAt;             // timestamp ms (Instant.now().toEpochMilli())
    public long startDateEpoch;        // fecha de inicio opcional (epoch ms)
    public long endDateEpoch;          // fecha de fin opcional (epoch ms)

    // Perfil resumido del usuario (para facilitar consumo por el front)
    public ProfileDto profile;

    // Duración y configuración
    public Integer durationWeeks;      // cantidad de semanas
    public String split;               // split usado ("push-pull-legs", etc)
    public String goal;                // objetivo ("hipertrofia", "fuerza", ...)

    // Contenido principal: semanas -> días -> items
    // Reutiliza las clases que ya definiste dentro de GenerateResponse
    public List<GenerateResponse.WeekDto> weeks;

    // Estadísticas / metadatos opcionales
    public Integer totalDays;
    public Integer totalExercises;
    public String notes;

    // Constructor vacío (POJO-style)
    public RoutineDto() {}
}

