CREATE TABLE periods (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(120) NOT NULL,
    cycle VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    open_at DATETIME2(3) NULL,
    deadline DATETIME2(3) NULL,
    auto_lock BIT NOT NULL CONSTRAINT df_periods_auto_lock DEFAULT 1,
    allow_edit_after_deadline BIT NOT NULL CONSTRAINT df_periods_allow_edit DEFAULT 0,
    allow_reopen_by_admin BIT NOT NULL CONSTRAINT df_periods_allow_reopen DEFAULT 1,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_periods_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_periods_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_periods PRIMARY KEY (id),
    CONSTRAINT ck_periods_date_range CHECK (end_date >= start_date),
    CONSTRAINT ck_periods_open_deadline CHECK (deadline IS NULL OR open_at IS NULL OR deadline > open_at),
    CONSTRAINT ck_periods_status CHECK (status IN ('draft', 'not_open', 'open', 'locked'))
);

CREATE TABLE period_applicable_users (
    period_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_period_applicable_users PRIMARY KEY (period_id, user_id),
    CONSTRAINT fk_period_applicable_users_period FOREIGN KEY (period_id) REFERENCES periods (id) ON DELETE CASCADE,
    CONSTRAINT fk_period_applicable_users_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE period_weekdays (
    period_id VARCHAR(64) NOT NULL,
    weekday TINYINT NOT NULL,
    CONSTRAINT pk_period_weekdays PRIMARY KEY (period_id, weekday),
    CONSTRAINT fk_period_weekdays_period FOREIGN KEY (period_id) REFERENCES periods (id) ON DELETE CASCADE,
    CONSTRAINT ck_period_weekdays_range CHECK (weekday BETWEEN 0 AND 6)
);

CREATE TABLE period_dates (
    id BIGINT IDENTITY(1,1) NOT NULL,
    period_id VARCHAR(64) NOT NULL,
    calendar_date DATE NOT NULL,
    date_type VARCHAR(20) NOT NULL,
    note NVARCHAR(300) NULL,
    CONSTRAINT pk_period_dates PRIMARY KEY (id),
    CONSTRAINT uq_period_dates_period_date_type UNIQUE (period_id, calendar_date, date_type),
    CONSTRAINT fk_period_dates_period FOREIGN KEY (period_id) REFERENCES periods (id) ON DELETE CASCADE,
    CONSTRAINT ck_period_dates_type CHECK (date_type IN ('exempt', 'closed'))
);

CREATE TABLE availability_registrations (
    id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    period_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    submitted_at DATETIME2(3) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_availability_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_availability_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_availability_registrations PRIMARY KEY (id),
    CONSTRAINT uq_availability_user_period UNIQUE (user_id, period_id),
    CONSTRAINT fk_availability_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_availability_period FOREIGN KEY (period_id) REFERENCES periods (id),
    CONSTRAINT ck_availability_status CHECK (status IN ('draft', 'submitted'))
);

CREATE TABLE availability_entries (
    id BIGINT IDENTITY(1,1) NOT NULL,
    registration_id VARCHAR(64) NOT NULL,
    available_date DATE NOT NULL,
    CONSTRAINT pk_availability_entries PRIMARY KEY (id),
    CONSTRAINT uq_availability_entries_registration_date UNIQUE (registration_id, available_date),
    CONSTRAINT fk_availability_entries_registration FOREIGN KEY (registration_id)
        REFERENCES availability_registrations (id) ON DELETE CASCADE
);

CREATE TABLE availability_slots (
    id BIGINT IDENTITY(1,1) NOT NULL,
    entry_id BIGINT NOT NULL,
    start_time TIME(0) NOT NULL,
    end_time TIME(0) NOT NULL,
    CONSTRAINT pk_availability_slots PRIMARY KEY (id),
    CONSTRAINT fk_availability_slots_entry FOREIGN KEY (entry_id)
        REFERENCES availability_entries (id) ON DELETE CASCADE,
    CONSTRAINT ck_availability_slots_nonzero CHECK (start_time <> end_time)
);

CREATE TABLE shifts (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(120) NOT NULL,
    shift_date DATE NOT NULL,
    start_time TIME(0) NOT NULL,
    end_time TIME(0) NOT NULL,
    assignment_mode VARCHAR(20) NOT NULL,
    total_required INT NOT NULL,
    note NVARCHAR(1000) NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_shifts_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_shifts_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_shifts PRIMARY KEY (id),
    CONSTRAINT ck_shifts_mode CHECK (assignment_mode IN ('level', 'user', 'combined')),
    CONSTRAINT ck_shifts_total_required CHECK (total_required > 0),
    CONSTRAINT ck_shifts_status CHECK (status IN ('not_scheduled', 'draft', 'full', 'understaffed', 'published'))
);

CREATE INDEX ix_shifts_date_status ON shifts (shift_date, status);

CREATE TABLE shift_level_requirements (
    shift_id VARCHAR(64) NOT NULL,
    level_id VARCHAR(64) NOT NULL,
    required_count INT NOT NULL,
    CONSTRAINT pk_shift_level_requirements PRIMARY KEY (shift_id, level_id),
    CONSTRAINT fk_shift_level_requirements_shift FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE CASCADE,
    CONSTRAINT fk_shift_level_requirements_level FOREIGN KEY (level_id) REFERENCES levels (id),
    CONSTRAINT ck_shift_level_requirements_count CHECK (required_count > 0)
);

CREATE TABLE shift_user_preferences (
    shift_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    preference_type VARCHAR(20) NOT NULL,
    CONSTRAINT pk_shift_user_preferences PRIMARY KEY (shift_id, user_id),
    CONSTRAINT fk_shift_user_preferences_shift FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE CASCADE,
    CONSTRAINT fk_shift_user_preferences_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_shift_user_preferences_type CHECK (preference_type IN ('mandatory', 'preferred'))
);

CREATE TABLE assignments (
    id VARCHAR(64) NOT NULL,
    shift_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    assigned_by VARCHAR(64) NOT NULL,
    assigned_at DATETIME2(3) NOT NULL CONSTRAINT df_assignments_assigned_at DEFAULT SYSUTCDATETIME(),
    reason NVARCHAR(1000) NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT df_assignments_status DEFAULT 'confirmed',
    CONSTRAINT pk_assignments PRIMARY KEY (id),
    CONSTRAINT uq_assignments_shift_user UNIQUE (shift_id, user_id),
    CONSTRAINT fk_assignments_shift FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE CASCADE,
    CONSTRAINT fk_assignments_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_assignments_status CHECK (status IN ('confirmed', 'cancelled'))
);

CREATE INDEX ix_assignments_user ON assignments (user_id, status);

CREATE TABLE schedule_rules (
    id VARCHAR(64) NOT NULL,
    standard_hours DECIMAL(6,2) NOT NULL,
    max_hours_per_day DECIMAL(5,2) NOT NULL,
    max_hours_per_week DECIMAL(6,2) NOT NULL,
    max_hours_per_period DECIMAL(7,2) NOT NULL,
    min_rest_between_shifts DECIMAL(4,2) NOT NULL,
    scheduling_priority VARCHAR(30) NOT NULL,
    understaffed_policy VARCHAR(20) NOT NULL,
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_schedule_rules_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_schedule_rules PRIMARY KEY (id),
    CONSTRAINT ck_schedule_rules_hours CHECK (
        standard_hours > 0 AND max_hours_per_day > 0 AND max_hours_per_week > 0
        AND max_hours_per_period > 0 AND min_rest_between_shifts >= 0
    ),
    CONSTRAINT ck_schedule_rules_priority CHECK (scheduling_priority IN ('most_needed', 'least_hours')),
    CONSTRAINT ck_schedule_rules_understaffed CHECK (understaffed_policy IN ('warn', 'block'))
);

CREATE TABLE schedule_level_targets (
    rules_id VARCHAR(64) NOT NULL,
    level_id VARCHAR(64) NOT NULL,
    target_rate DECIMAL(5,2) NOT NULL,
    CONSTRAINT pk_schedule_level_targets PRIMARY KEY (rules_id, level_id),
    CONSTRAINT fk_schedule_level_targets_rules FOREIGN KEY (rules_id) REFERENCES schedule_rules (id) ON DELETE CASCADE,
    CONSTRAINT fk_schedule_level_targets_level FOREIGN KEY (level_id) REFERENCES levels (id),
    CONSTRAINT ck_schedule_level_targets_rate CHECK (target_rate BETWEEN 0 AND 200)
);

CREATE TABLE level_substitutions (
    rules_id VARCHAR(64) NOT NULL,
    higher_level_id VARCHAR(64) NOT NULL,
    lower_level_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_level_substitutions PRIMARY KEY (rules_id, higher_level_id, lower_level_id),
    CONSTRAINT fk_level_substitutions_rules FOREIGN KEY (rules_id) REFERENCES schedule_rules (id) ON DELETE CASCADE,
    CONSTRAINT fk_level_substitutions_higher FOREIGN KEY (higher_level_id) REFERENCES levels (id),
    CONSTRAINT fk_level_substitutions_lower FOREIGN KEY (lower_level_id) REFERENCES levels (id),
    CONSTRAINT ck_level_substitutions_not_same CHECK (higher_level_id <> lower_level_id)
);

CREATE TABLE history (
    id VARCHAR(64) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    title NVARCHAR(200) NOT NULL,
    detail NVARCHAR(2000) NULL,
    actor_id VARCHAR(64) NULL,
    actor_name NVARCHAR(100) NOT NULL,
    occurred_at DATETIME2(3) NOT NULL CONSTRAINT df_history_occurred_at DEFAULT SYSUTCDATETIME(),
    before_json NVARCHAR(MAX) NULL,
    after_json NVARCHAR(MAX) NULL,
    reason NVARCHAR(1000) NULL,
    CONSTRAINT pk_history PRIMARY KEY (id),
    CONSTRAINT fk_history_actor FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT ck_history_before_json CHECK (before_json IS NULL OR ISJSON(before_json) = 1),
    CONSTRAINT ck_history_after_json CHECK (after_json IS NULL OR ISJSON(after_json) = 1)
);

CREATE INDEX ix_history_occurred_at ON history (occurred_at DESC);

INSERT INTO schedule_rules (
    id, standard_hours, max_hours_per_day, max_hours_per_week, max_hours_per_period,
    min_rest_between_shifts, scheduling_priority, understaffed_policy
)
VALUES ('rules_main', 80, 12, 48, 80, 8, 'most_needed', 'warn');

INSERT INTO schedule_level_targets (rules_id, level_id, target_rate)
VALUES
    ('rules_main', 'lvl1', 75),
    ('rules_main', 'lvl2', 100),
    ('rules_main', 'lvl3', 100),
    ('rules_main', 'lvl4', 100);

INSERT INTO level_substitutions (rules_id, higher_level_id, lower_level_id)
VALUES
    ('rules_main', 'lvl4', 'lvl3'),
    ('rules_main', 'lvl4', 'lvl2'),
    ('rules_main', 'lvl4', 'lvl1'),
    ('rules_main', 'lvl3', 'lvl2'),
    ('rules_main', 'lvl3', 'lvl1'),
    ('rules_main', 'lvl2', 'lvl1');