CREATE TABLE levels (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(100) NOT NULL,
    description NVARCHAR(500) NULL,
    color CHAR(7) NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_levels_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_levels PRIMARY KEY (id),
    CONSTRAINT uq_levels_name UNIQUE (name),
    CONSTRAINT uq_levels_sort_order UNIQUE (sort_order)
);

CREATE TABLE users (
    id VARCHAR(64) NOT NULL,
    employee_code VARCHAR(20) NOT NULL,
    username VARCHAR(30) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name NVARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    level_id VARCHAR(64) NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT df_users_status DEFAULT 'active',
    target_rate DECIMAL(5, 2) NOT NULL CONSTRAINT df_users_target_rate DEFAULT 100,
    phone VARCHAR(30) NULL,
    email NVARCHAR(254) NULL,
    join_date DATE NULL,
    note NVARCHAR(1000) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_users_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_users_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_employee_code UNIQUE (employee_code),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT fk_users_level FOREIGN KEY (level_id) REFERENCES levels (id),
    CONSTRAINT ck_users_role CHECK (role IN ('admin', 'user')),
    CONSTRAINT ck_users_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_users_target_rate CHECK (target_rate >= 0 AND target_rate <= 200)
);

CREATE INDEX ix_users_level_id ON users (level_id);