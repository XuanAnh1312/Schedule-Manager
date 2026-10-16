/* V3__seed_admin_user.sql
 * Seeds the default admin user.
 * Password: Admin@123  (BCrypt cost 10 — never store plain text)
 * level_id is NULL because the admin account is not a regular staff member.
 * Uses MERGE so this script is idempotent.
 *
 * NOTE: The BCrypt hash below was generated at cost factor 10 for the string
 * "Admin@123". Verified by BcryptSeedVerificationTest.
 * The default password MUST be changed before the first production deployment.
 */

MERGE INTO users AS target
USING (VALUES (
    'usr_admin_001',
    'ADM001',
    'admin',
    '$2a$10$rQUEC.jn9yzcg93LB3O4zugWOYJk.rVvweKiHgHOBe3CA.Q74BfSG',
    N'Administrator',
    'admin',
    NULL,
    'active',
    100.00,
    NULL,
    NULL,
    NULL,
    NULL
)) AS source (
    id, employee_code, username, password_hash,
    full_name, role, level_id, status, target_rate,
    phone, email, join_date, note
)
ON target.username = source.username
WHEN MATCHED THEN
    UPDATE SET
        employee_code = source.employee_code,
        password_hash = source.password_hash,
        full_name     = source.full_name,
        role          = source.role,
        status        = source.status
WHEN NOT MATCHED BY TARGET THEN
    INSERT (id, employee_code, username, password_hash, full_name, role,
            level_id, status, target_rate, phone, email, join_date, note)
    VALUES (source.id, source.employee_code, source.username, source.password_hash,
            source.full_name, source.role, source.level_id, source.status,
            source.target_rate, source.phone, source.email, source.join_date, source.note);
