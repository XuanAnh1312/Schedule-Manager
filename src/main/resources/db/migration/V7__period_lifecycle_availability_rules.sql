/*
 * V7 — Period lifecycle, availability slot types and configurable scheduling rules
 * (issue #5: TU-01 … TU-07).
 *
 * 1. periods.status becomes draft | open | locked | archived (agreed with the schedule
 *    owner on 2026-10-29). "not_open" is dropped: "upcoming" is derived from open_at.
 *    "scheduling" / "published" belong to the schedule-plan lifecycle, not to periods.
 *    periods.version enables optimistic locking for concurrent admin edits.
 * 2. availability_slots.slot_type distinguishes "available" from "unavailable" time.
 * 3. rule_changes / rule_settings / rule_revision hold scoped, versioned rule values with
 *    preview → confirm → history. No rule values are seeded: the V5 schedule_rules row is
 *    only offered as a suggestion that an admin must confirm (TU-07).
 *
 * Existing columns of schedule_rules and level_substitutions are kept unchanged because
 * other modules read them.
 */

-- ── 1. Period lifecycle ─────────────────────────────────────────────────────
UPDATE periods SET status = 'draft' WHERE status = 'not_open';

ALTER TABLE periods DROP CONSTRAINT ck_periods_status;

ALTER TABLE periods ADD CONSTRAINT ck_periods_status
    CHECK (status IN ('draft', 'open', 'locked', 'archived'));

ALTER TABLE periods ADD version INT NOT NULL CONSTRAINT df_periods_version DEFAULT 0;

-- Used by the auto-lock job: open periods whose deadline has passed.
CREATE INDEX ix_periods_status_deadline ON periods (status, deadline);

-- ── 2. Availability slot type ───────────────────────────────────────────────
ALTER TABLE availability_slots ADD slot_type VARCHAR(20) NOT NULL
    CONSTRAINT df_availability_slots_type DEFAULT 'available'
    CONSTRAINT ck_availability_slots_type CHECK (slot_type IN ('available', 'unavailable'));

CREATE INDEX ix_availability_registrations_period ON availability_registrations (period_id, status);

-- ── 3. Scoped, versioned scheduling rules ──────────────────────────────────
CREATE TABLE rule_changes (
    id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    base_revision INT NOT NULL,
    reason NVARCHAR(1000) NOT NULL,
    items_json NVARCHAR(MAX) NOT NULL,
    preview_json NVARCHAR(MAX) NULL,
    created_by VARCHAR(64) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_rule_changes_created_at DEFAULT SYSUTCDATETIME(),
    applied_by VARCHAR(64) NULL,
    applied_at DATETIME2(3) NULL,
    CONSTRAINT pk_rule_changes PRIMARY KEY (id),
    CONSTRAINT fk_rule_changes_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_rule_changes_applied_by FOREIGN KEY (applied_by) REFERENCES users (id),
    CONSTRAINT ck_rule_changes_status CHECK (status IN ('previewed', 'applied', 'cancelled')),
    CONSTRAINT ck_rule_changes_items_json CHECK (ISJSON(items_json) = 1),
    CONSTRAINT ck_rule_changes_preview_json CHECK (preview_json IS NULL OR ISJSON(preview_json) = 1)
);

CREATE INDEX ix_rule_changes_status_created ON rule_changes (status, created_at DESC);

CREATE TABLE rule_settings (
    id VARCHAR(64) NOT NULL,
    param_key VARCHAR(60) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id VARCHAR(64) NULL,
    value_text NVARCHAR(1000) NOT NULL,
    effective_from DATE NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT df_rule_settings_status DEFAULT 'active',
    change_id VARCHAR(64) NOT NULL,
    created_by VARCHAR(64) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_rule_settings_created_at DEFAULT SYSUTCDATETIME(),
    ended_by_change_id VARCHAR(64) NULL,
    ended_at DATETIME2(3) NULL,
    CONSTRAINT pk_rule_settings PRIMARY KEY (id),
    CONSTRAINT fk_rule_settings_change FOREIGN KEY (change_id) REFERENCES rule_changes (id),
    CONSTRAINT fk_rule_settings_ended_change FOREIGN KEY (ended_by_change_id) REFERENCES rule_changes (id),
    CONSTRAINT fk_rule_settings_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_rule_settings_scope_type
        CHECK (scope_type IN ('company', 'branch', 'employee_type', 'group', 'user')),
    CONSTRAINT ck_rule_settings_scope_id CHECK (
        (scope_type = 'company' AND scope_id IS NULL) OR (scope_type <> 'company' AND scope_id IS NOT NULL)
    ),
    CONSTRAINT ck_rule_settings_status CHECK (status IN ('active', 'superseded', 'removed'))
);

-- One active value per parameter, scope and effective date (NULL scope_id = company).
CREATE UNIQUE INDEX uq_rule_settings_active
    ON rule_settings (param_key, scope_type, scope_id, effective_from)
    WHERE status = 'active';

CREATE INDEX ix_rule_settings_scope ON rule_settings (scope_type, scope_id, status);

-- Single-row counter: a preview is applied only if no other change was applied meanwhile.
CREATE TABLE rule_revision (
    id TINYINT NOT NULL,
    revision INT NOT NULL,
    CONSTRAINT pk_rule_revision PRIMARY KEY (id),
    CONSTRAINT ck_rule_revision_single_row CHECK (id = 1)
);

INSERT INTO rule_revision (id, revision) VALUES (1, 0);
