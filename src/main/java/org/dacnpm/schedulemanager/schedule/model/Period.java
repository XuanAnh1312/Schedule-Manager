package org.dacnpm.schedulemanager.schedule.model;

import java.time.LocalDate;

/** Kỳ phân lịch (bảng periods). Ngày bắt đầu và kết thúc đều thuộc kỳ. */
public record Period(String id, String name, LocalDate startDate, LocalDate endDate) {

    public Period {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Kỳ " + id + " có khoảng ngày không hợp lệ.");
        }
    }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
