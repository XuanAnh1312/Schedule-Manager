package org.dacnpm.schedulemanager.api.auth;

/** Request body for POST /api/auth/change-password. */
public record ChangePasswordRequest(String currentPassword, String newPassword) {}
