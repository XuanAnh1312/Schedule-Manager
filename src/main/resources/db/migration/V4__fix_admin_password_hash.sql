/* V4__fix_admin_password_hash.sql
 * The BCrypt hash stored by V3__seed_admin_user.sql did not match the
 * documented dev password "Admin@123". This migration replaces it with the
 * correct hash generated at BCrypt cost 10.
 *
 * Verified by: BcryptSeedVerificationTest and AuthServiceTest.
 *
 * IMPORTANT: This migration only fixes the dev seed. The default admin
 * password MUST be changed before the first production deployment.
 */

UPDATE users
SET    password_hash = '$2a$10$rQUEC.jn9yzcg93LB3O4zugWOYJk.rVvweKiHgHOBe3CA.Q74BfSG'
WHERE  username      = 'admin'
AND    id            = 'usr_admin_001';
