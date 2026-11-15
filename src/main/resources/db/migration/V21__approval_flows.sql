/*
 * V21 — BAO-04: duyệt nhiều bước theo quản lý trực tiếp (CO-01 phần 2) cho đơn nghỉ và yêu cầu đổi/thay ca.
 *
 * SỐ MIGRATION TẠM: phải xin Anh xác nhận số V21 trước khi commit (đổi tên file nếu Anh cấp số khác).
 *
 *   - approval_flows : cấu hình quy trình theo loại đối tượng (leave | shift_request), riêng đơn nghỉ có thể theo loại nghỉ
 *                      (leave_type_id NULL = mặc định cho mọi loại nghỉ chưa cấu hình riêng).
 *       admin_only         : như trước — chỉ Admin duyệt (mặc định, giữ tương thích)
 *       manager            : 1 bước — quản lý trực tiếp (users.manager_id, V18)
 *       manager_then_admin : 2 bước — quản lý trực tiếp → Admin/HR
 *   - approval_steps : từng bước của một đơn/yêu cầu. Bước quản lý chốt người duyệt lúc tạo; nhân viên không có quản lý
 *                      trực tiếp → bước quản lý "skipped" và luôn có bước Admin để không kẹt đơn.
 *   Admin (quyền toàn công ty V1) duyệt ở bất kỳ bước nào = duyệt cuối; các bước còn lại ghi "skipped" kèm lý do.
 *   Lịch/số dư phép chỉ thay đổi ở bước cuối.
 */
CREATE TABLE approval_flows (
    id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(20) NOT NULL,
    leave_type_id VARCHAR(64) NULL,
    mode VARCHAR(30) NOT NULL,
    updated_by VARCHAR(64) NULL,
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_approval_flows_updated_at DEFAULT SYSUTCDATETIME(),
    version INT NOT NULL CONSTRAINT df_approval_flows_version DEFAULT 0,
    CONSTRAINT pk_approval_flows PRIMARY KEY (id),
    CONSTRAINT fk_approval_flows_leave_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id),
    CONSTRAINT fk_approval_flows_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_approval_flows_subject CHECK (subject_type IN ('leave', 'shift_request')),
    CONSTRAINT ck_approval_flows_mode CHECK (mode IN ('admin_only', 'manager', 'manager_then_admin')),
    CONSTRAINT ck_approval_flows_leave_type CHECK (subject_type = 'leave' OR leave_type_id IS NULL)
);
-- Một cấu hình cho mỗi (đối tượng, loại nghỉ); NULL được coi là một giá trị nên chỉ có một dòng mặc định.
CREATE UNIQUE INDEX ux_approval_flows_scope ON approval_flows (subject_type, leave_type_id);

CREATE TABLE approval_steps (
    id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(20) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    step_no INT NOT NULL,
    approver_kind VARCHAR(10) NOT NULL,               -- manager | admin
    approver_id VARCHAR(64) NULL,                     -- người duyệt cụ thể (bước quản lý); NULL = mọi Admin
    status VARCHAR(10) NOT NULL,
    decided_by VARCHAR(64) NULL,
    decided_at DATETIME2(3) NULL,
    note NVARCHAR(1000) NULL,
    version INT NOT NULL CONSTRAINT df_approval_steps_version DEFAULT 0,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_approval_steps_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_approval_steps PRIMARY KEY (id),
    CONSTRAINT uq_approval_steps_order UNIQUE (subject_type, subject_id, step_no),
    CONSTRAINT fk_approval_steps_approver FOREIGN KEY (approver_id) REFERENCES users (id),
    CONSTRAINT fk_approval_steps_decided_by FOREIGN KEY (decided_by) REFERENCES users (id),
    CONSTRAINT ck_approval_steps_subject CHECK (subject_type IN ('leave', 'shift_request')),
    CONSTRAINT ck_approval_steps_kind CHECK (approver_kind IN ('manager', 'admin')),
    CONSTRAINT ck_approval_steps_status CHECK (status IN ('waiting', 'pending', 'approved', 'rejected', 'skipped')),
    CONSTRAINT ck_approval_steps_manager CHECK (approver_kind = 'admin' OR approver_id IS NOT NULL OR status = 'skipped')
);
CREATE INDEX ix_approval_steps_approver ON approval_steps (approver_id, status);

-- Mặc định giữ hành vi cũ (chỉ Admin duyệt). Admin đổi trong màn hình "Quy trình duyệt".
INSERT INTO approval_flows (id, subject_type, leave_type_id, mode) VALUES
    ('flow_leave_default', 'leave', NULL, 'admin_only'),
    ('flow_shift_request', 'shift_request', NULL, 'admin_only');
