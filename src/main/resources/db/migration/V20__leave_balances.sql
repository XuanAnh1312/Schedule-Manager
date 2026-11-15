/*
 * V20 — BAO-03: sổ phép (CO-01 phần 1) — phép được hưởng / đã dùng / đang chờ / còn lại.
 *
 * SỐ MIGRATION TẠM: phải xin Anh xác nhận số V20 trước khi commit (đổi tên file nếu Anh cấp số khác).
 *
 * Nguyên tắc:
 *   - Số dư là dữ liệu dẫn xuất từ sổ cái: mọi thay đổi (cấp, giữ chỗ, trả chỗ, trừ, hoàn, điều chỉnh) đều có 1 dòng
 *     leave_balance_ledger kèm lý do/người thực hiện; leave_balances giữ tổng hợp + version để khóa lạc quan.
 *   - Chính sách theo loại nghỉ × loại nhân viên (users.employee_type_id, V15) × ngày hiệu lực. employee_type_id NULL =
 *     áp dụng mọi loại nhân viên chưa có chính sách riêng. KHÔNG mặc định Part-time mất quyền nghỉ.
 *   - Không tự điền số ngày khi thiếu chính sách: loại nghỉ có theo dõi số dư mà không có chính sách hiệu lực → từ chối
 *     tạo đơn và báo Admin cấu hình (cùng tinh thần AC-13).
 */

-- Loại nghỉ nào trừ vào sổ phép. Mặc định 0 để không đổi hành vi của loại nghỉ cũ.
ALTER TABLE leave_types ADD tracks_balance BIT NOT NULL CONSTRAINT df_leave_types_tracks_balance DEFAULT 0;
EXEC('UPDATE leave_types SET tracks_balance = 1 WHERE id = ''leave_annual''');

-- Số ngày của đơn (hỗ trợ nửa ngày). NULL với đơn tạo trước V20: tính lại từ start/end khi cần.
ALTER TABLE leave_requests ADD half_day BIT NOT NULL CONSTRAINT df_leave_requests_half_day DEFAULT 0;
ALTER TABLE leave_requests ADD days_requested DECIMAL(5, 1) NULL;
ALTER TABLE leave_requests ADD CONSTRAINT ck_leave_requests_half_day CHECK (half_day = 0 OR start_date = end_date);
ALTER TABLE leave_requests ADD CONSTRAINT ck_leave_requests_days CHECK (days_requested IS NULL OR days_requested > 0);

CREATE TABLE leave_policies (
    id VARCHAR(64) NOT NULL,
    leave_type_id VARCHAR(64) NOT NULL,
    employee_type_id VARCHAR(64) NULL,                 -- NULL = mọi loại nhân viên chưa có chính sách riêng
    days_per_year DECIMAL(5, 1) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,                            -- NULL = còn hiệu lực
    note NVARCHAR(500) NULL,
    created_by VARCHAR(64) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_leave_policies_created_at DEFAULT SYSUTCDATETIME(),
    version INT NOT NULL CONSTRAINT df_leave_policies_version DEFAULT 0,
    CONSTRAINT pk_leave_policies PRIMARY KEY (id),
    CONSTRAINT fk_leave_policies_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id),
    CONSTRAINT fk_leave_policies_employee_type FOREIGN KEY (employee_type_id) REFERENCES employee_types (id),
    CONSTRAINT fk_leave_policies_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_leave_policies_days CHECK (days_per_year >= 0 AND days_per_year <= 366),
    CONSTRAINT ck_leave_policies_range CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX ix_leave_policies_lookup ON leave_policies (leave_type_id, employee_type_id, effective_from);

CREATE TABLE leave_balances (
    id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    balance_year INT NOT NULL,
    leave_type_id VARCHAR(64) NOT NULL,
    policy_id VARCHAR(64) NULL,                        -- chính sách dùng để cấp phép năm
    entitled DECIMAL(6, 1) NOT NULL,                   -- được hưởng theo chính sách
    adjusted DECIMAL(6, 1) NOT NULL CONSTRAINT df_leave_balances_adjusted DEFAULT 0,  -- Admin điều chỉnh (+/-)
    used DECIMAL(6, 1) NOT NULL CONSTRAINT df_leave_balances_used DEFAULT 0,          -- đơn đã duyệt
    pending DECIMAL(6, 1) NOT NULL CONSTRAINT df_leave_balances_pending DEFAULT 0,    -- đơn đang chờ (giữ chỗ)
    version INT NOT NULL CONSTRAINT df_leave_balances_version DEFAULT 0,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_leave_balances_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_leave_balances_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_leave_balances PRIMARY KEY (id),
    CONSTRAINT uq_leave_balances_user_year_type UNIQUE (user_id, balance_year, leave_type_id),
    CONSTRAINT fk_leave_balances_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_leave_balances_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id),
    CONSTRAINT fk_leave_balances_policy FOREIGN KEY (policy_id) REFERENCES leave_policies (id),
    CONSTRAINT ck_leave_balances_non_negative CHECK (entitled >= 0 AND used >= 0 AND pending >= 0),
    -- Lớp chặn cuối: không bao giờ dùng + giữ chỗ vượt quá phép được hưởng sau điều chỉnh.
    CONSTRAINT ck_leave_balances_remaining CHECK (entitled + adjusted - used - pending >= 0)
);

CREATE TABLE leave_balance_ledger (
    id VARCHAR(64) NOT NULL,
    balance_id VARCHAR(64) NOT NULL,
    entry_type VARCHAR(20) NOT NULL,
    days DECIMAL(6, 1) NOT NULL,                       -- luôn dương; chiều tác động suy ra từ entry_type
    leave_request_id VARCHAR(64) NULL,
    reason NVARCHAR(1000) NULL,
    actor_id VARCHAR(64) NULL,                         -- NULL = hệ thống (cấp phép năm tự động)
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_leave_balance_ledger_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_leave_balance_ledger PRIMARY KEY (id),
    CONSTRAINT fk_leave_balance_ledger_balance FOREIGN KEY (balance_id) REFERENCES leave_balances (id),
    CONSTRAINT fk_leave_balance_ledger_request FOREIGN KEY (leave_request_id) REFERENCES leave_requests (id),
    CONSTRAINT fk_leave_balance_ledger_actor FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT ck_leave_balance_ledger_type CHECK (entry_type IN (
        'grant',    -- cấp phép năm theo chính sách        → entitled +
        'reserve',  -- đơn gửi, giữ chỗ                     → pending +
        'release',  -- đơn bị từ chối/hủy khi đang chờ       → pending -
        'consume',  -- đơn được duyệt                        → pending -, used +
        'restore',  -- hủy đơn đã duyệt                      → used -
        'adjust_up', 'adjust_down')),                     -- Admin điều chỉnh  → adjusted +/-
    CONSTRAINT ck_leave_balance_ledger_days CHECK (days > 0)
);
CREATE INDEX ix_leave_balance_ledger_balance ON leave_balance_ledger (balance_id, created_at);
CREATE INDEX ix_leave_balance_ledger_request ON leave_balance_ledger (leave_request_id);

-- Chính sách khởi tạo: 12 ngày/năm cho mọi loại nhân viên — mức tối thiểu điều kiện bình thường theo Bộ luật Lao động
-- 2019, Điều 113. GIẢ ĐỊNH chờ nhóm chốt; Admin đổi bằng cách kết thúc chính sách này và tạo chính sách mới.
EXEC('INSERT INTO leave_policies (id, leave_type_id, employee_type_id, days_per_year, effective_from, note)
      VALUES (''lpol_annual_default'', ''leave_annual'', NULL, 12, ''2026-01-01'',
              N''Mặc định 12 ngày/năm (BLLĐ 2019 Điều 113) — giả định chờ nhóm chốt'')');
