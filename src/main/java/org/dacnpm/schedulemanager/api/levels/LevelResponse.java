package org.dacnpm.schedulemanager.api.levels;

public record LevelResponse(
        String id,
        String name,
        String description,
        String color,
        int order) {
}