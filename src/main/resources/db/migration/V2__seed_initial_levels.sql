/* V2__seed_initial_levels.sql
 * Seed 4 initial levels matching the frontend demo data in js/storage.js.
 * Uses MERGE so this script is idempotent: safe to re-run and safe if
 * Flyway ever repairs and re-applies it.
 */

MERGE INTO levels AS target
USING (VALUES
    ('lvl1', N'Level 1', N'Nhân viên mới',       '#7c3aed', 1),
    ('lvl2', N'Level 2', N'Nhân viên chính thức', '#be185d', 2),
    ('lvl3', N'Level 3', N'Nhân viên kỳ cựu',     '#c2410c', 3),
    ('lvl4', N'Level 4', N'Trưởng ca',             '#065f46', 4)
) AS source (id, name, description, color, sort_order)
ON target.id = source.id
WHEN MATCHED THEN
    UPDATE SET
        name        = source.name,
        description = source.description,
        color       = source.color,
        sort_order  = source.sort_order
WHEN NOT MATCHED BY TARGET THEN
    INSERT (id, name, description, color, sort_order)
    VALUES (source.id, source.name, source.description, source.color, source.sort_order);
