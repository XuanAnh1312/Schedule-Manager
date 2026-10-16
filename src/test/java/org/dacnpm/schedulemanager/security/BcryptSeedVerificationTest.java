package org.dacnpm.schedulemanager.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that the BCrypt hash stored in V3__seed_admin_user.sql matches
 * the dev password documented in the migration comment.
 *
 * <p>This test is the executable proof that the seed and its documentation
 * are consistent. If the hash is regenerated, this test must be updated too.
 * No plaintext password is ever stored in the database or committed to VCS
 * beyond this test — which is acceptable because the value is a known
 * dev-only credential explicitly documented in the migration comment.
 *
 * <p><strong>Production note:</strong> The default admin password MUST be
 * changed before the first production deployment. This seed is for
 * development and CI only.
 */
class BcryptSeedVerificationTest {

    /**
     * The exact hash stored in V3__seed_admin_user.sql (updated by V4 migration).
     * Update this constant if the migration hash is changed.
     */
    private static final String V3_ADMIN_HASH =
            "$2a$10$rQUEC.jn9yzcg93LB3O4zugWOYJk.rVvweKiHgHOBe3CA.Q74BfSG";

    /**
     * The dev password documented in the V3 migration comment:
     * "Password: Admin@123  (BCrypt cost 10 — never store plain text)"
     */
    private static final String V3_ADMIN_DEV_PASSWORD = "Admin@123";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void v3_adminHash_matchesDocumentedDevPassword() {
        assertTrue(
                encoder.matches(V3_ADMIN_DEV_PASSWORD, V3_ADMIN_HASH),
                "The BCrypt hash in V3__seed_admin_user.sql must match the dev password " +
                "documented in the migration comment. If the hash was regenerated, " +
                "update V3_ADMIN_HASH in this test."
        );
    }

    @Test
    void v3_adminHash_doesNotMatchWrongPassword() {
        assertFalse(
                encoder.matches("WrongPassword", V3_ADMIN_HASH),
                "A wrong password must NOT match the seed hash."
        );
    }
}
