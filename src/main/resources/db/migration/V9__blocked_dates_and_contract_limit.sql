-- TW-02: nguồn dữ liệu cho "không thể làm" và giới hạn giờ theo hợp đồng. Không sửa V1-V8.

-- Ngày nhân viên không thể làm việc. source = leave dành cho module nghỉ phép ghi khi đơn được duyệt.
CREATE TABLE user_blocked_dates (
    user_id VARCHAR(64) NOT NULL,
    blocked_date DATE NOT NULL,
    reason NVARCHAR(300) NULL,
    source VARCHAR(20) NOT NULL CONSTRAINT df_user_blocked_dates_source DEFAULT 'manual',
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_user_blocked_dates_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_user_blocked_dates PRIMARY KEY (user_id, blocked_date),
    CONSTRAINT fk_user_blocked_dates_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_blocked_dates_source CHECK (source IN ('manual', 'leave'))
);

-- Giới hạn giờ/tuần theo hợp đồng của từng nhân viên; NULL nghĩa là không có giới hạn riêng.
ALTER TABLE users ADD
    contract_max_hours_per_week DECIMAL(5,2) NULL
        CONSTRAINT ck_users_contract_max_week CHECK (contract_max_hours_per_week IS NULL OR contract_max_hours_per_week > 0);
