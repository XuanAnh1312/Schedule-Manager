-- TW-01: quản lý ca. Không sửa V1-V7.
-- Danh mục tối thiểu: địa điểm, chức vụ, kỹ năng, chứng chỉ.

CREATE TABLE locations (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(120) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_locations_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_locations PRIMARY KEY (id),
    CONSTRAINT uq_locations_name UNIQUE (name)
);

CREATE TABLE positions (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(120) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_positions_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_positions PRIMARY KEY (id),
    CONSTRAINT uq_positions_name UNIQUE (name)
);

CREATE TABLE skills (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(120) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_skills_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_skills PRIMARY KEY (id),
    CONSTRAINT uq_skills_name UNIQUE (name)
);

CREATE TABLE certificates (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(120) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_certificates_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_certificates PRIMARY KEY (id),
    CONSTRAINT uq_certificates_name UNIQUE (name)
);

-- Gắn nhân viên với danh mục. Nhân viên không có dòng user_locations được coi là không giới hạn địa điểm.
CREATE TABLE user_locations (
    user_id VARCHAR(64) NOT NULL,
    location_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_user_locations PRIMARY KEY (user_id, location_id),
    CONSTRAINT fk_user_locations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_locations_location FOREIGN KEY (location_id) REFERENCES locations (id)
);

CREATE TABLE user_positions (
    user_id VARCHAR(64) NOT NULL,
    position_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_user_positions PRIMARY KEY (user_id, position_id),
    CONSTRAINT fk_user_positions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_positions_position FOREIGN KEY (position_id) REFERENCES positions (id)
);

CREATE TABLE user_skills (
    user_id VARCHAR(64) NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_user_skills PRIMARY KEY (user_id, skill_id),
    CONSTRAINT fk_user_skills_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_skills_skill FOREIGN KEY (skill_id) REFERENCES skills (id)
);

-- valid_until NULL nghĩa là chứng chỉ không hết hạn.
CREATE TABLE user_certificates (
    user_id VARCHAR(64) NOT NULL,
    certificate_id VARCHAR(64) NOT NULL,
    valid_until DATE NULL,
    CONSTRAINT pk_user_certificates PRIMARY KEY (user_id, certificate_id),
    CONSTRAINT fk_user_certificates_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_certificates_certificate FOREIGN KEY (certificate_id) REFERENCES certificates (id)
);

-- Mở rộng ca. min_required/max_allowed NULL nghĩa là dùng total_required.
ALTER TABLE shifts ADD
    location_id VARCHAR(64) NULL
        CONSTRAINT fk_shifts_location FOREIGN KEY REFERENCES locations (id);

ALTER TABLE shifts ADD
    min_required INT NULL
        CONSTRAINT ck_shifts_min_required CHECK (min_required IS NULL OR min_required > 0);

ALTER TABLE shifts ADD
    max_allowed INT NULL
        CONSTRAINT ck_shifts_max_allowed CHECK (max_allowed IS NULL OR max_allowed > 0);

ALTER TABLE shifts ADD
    is_emergency BIT NOT NULL
        CONSTRAINT df_shifts_is_emergency DEFAULT 0;

ALTER TABLE shifts ADD series_id VARCHAR(64) NULL;

ALTER TABLE shifts ADD cancelled_at DATETIME2(3) NULL;

ALTER TABLE shifts ADD
    cancelled_by VARCHAR(64) NULL
        CONSTRAINT fk_shifts_cancelled_by FOREIGN KEY REFERENCES users (id);

ALTER TABLE shifts ADD cancel_reason NVARCHAR(1000) NULL;

-- Ràng buộc nhiều cột và chỉ mục dùng EXEC để không bị lỗi biên dịch lô khi cột vừa được thêm.
EXEC('ALTER TABLE shifts ADD CONSTRAINT ck_shifts_min_total CHECK (min_required IS NULL OR min_required <= total_required)');
EXEC('ALTER TABLE shifts ADD CONSTRAINT ck_shifts_max_total CHECK (max_allowed IS NULL OR max_allowed >= total_required)');
EXEC('ALTER TABLE shifts ADD CONSTRAINT ck_shifts_cancel_reason CHECK (cancelled_at IS NULL OR (cancelled_by IS NOT NULL AND cancel_reason IS NOT NULL))');
EXEC('CREATE INDEX ix_shifts_location_date ON shifts (location_id, shift_date)');
EXEC('CREATE INDEX ix_shifts_series ON shifts (series_id)');

CREATE TABLE shift_skill_requirements (
    shift_id VARCHAR(64) NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_shift_skill_requirements PRIMARY KEY (shift_id, skill_id),
    CONSTRAINT fk_shift_skill_requirements_shift FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE CASCADE,
    CONSTRAINT fk_shift_skill_requirements_skill FOREIGN KEY (skill_id) REFERENCES skills (id)
);

CREATE TABLE shift_certificate_requirements (
    shift_id VARCHAR(64) NOT NULL,
    certificate_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_shift_certificate_requirements PRIMARY KEY (shift_id, certificate_id),
    CONSTRAINT fk_shift_certificate_requirements_shift FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE CASCADE,
    CONSTRAINT fk_shift_certificate_requirements_certificate FOREIGN KEY (certificate_id) REFERENCES certificates (id)
);

-- Chức vụ yêu cầu: nhân viên phải giữ ít nhất một trong các chức vụ được liệt kê.
CREATE TABLE shift_position_requirements (
    shift_id VARCHAR(64) NOT NULL,
    position_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_shift_position_requirements PRIMARY KEY (shift_id, position_id),
    CONSTRAINT fk_shift_position_requirements_shift FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE CASCADE,
    CONSTRAINT fk_shift_position_requirements_position FOREIGN KEY (position_id) REFERENCES positions (id)
);
