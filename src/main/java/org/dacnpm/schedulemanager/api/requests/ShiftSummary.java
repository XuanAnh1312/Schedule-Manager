package org.dacnpm.schedulemanager.api.requests;

import java.time.LocalDate;
import java.time.LocalTime;

/** Tóm tắt ca hiển thị trong yêu cầu. */
public record ShiftSummary(String id, String name, LocalDate date, LocalTime startTime, LocalTime endTime) {}
