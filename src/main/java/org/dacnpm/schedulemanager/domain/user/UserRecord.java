package org.dacnpm.schedulemanager.domain.user;

/**
 * Internal domain object mapping a row from the users table.
 * Never exposed directly in API responses.
 */
public record UserRecord(
        String id,
        String username,
        String passwordHash,
        String role,
        String status
) {}
