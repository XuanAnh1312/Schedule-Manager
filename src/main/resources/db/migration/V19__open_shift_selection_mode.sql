/*
 * V19 — BAO-02: ca trống tự chọn người (selection_mode = auto).
 *
 * SỐ MIGRATION TẠM: phải xin Anh xác nhận số V19 trước khi commit. Nếu Anh cấp số khác, chỉ cần đổi tên file;
 * nội dung không phụ thuộc số.
 *
 *   - selection_mode    : manual (Admin chọn từng người, như cũ) | auto (hết hạn nhận → hệ thống tự chọn theo quy tắc)
 *   - auto_processed_at : thời điểm (UTC) job đã chạy chọn tự động; khác NULL = đã xử lý, không chạy lần hai
 *   - auto_rank / auto_note trên open_shift_claims: thứ tự xếp hạng và lý do chọn/loại, để giải thích kết quả
 */
ALTER TABLE open_shifts ADD selection_mode VARCHAR(10) NOT NULL
    CONSTRAINT df_open_shifts_selection_mode DEFAULT 'manual';
ALTER TABLE open_shifts ADD auto_processed_at DATETIME2(3) NULL;
ALTER TABLE open_shifts ADD CONSTRAINT ck_open_shifts_selection_mode CHECK (selection_mode IN ('manual', 'auto'));

ALTER TABLE open_shift_claims ADD auto_rank INT NULL;
ALTER TABLE open_shift_claims ADD auto_note NVARCHAR(500) NULL;

-- Job chỉ quét ca trống auto còn mở, chưa xử lý. EXEC: chỉ mục lọc tham chiếu cột vừa thêm trong cùng batch.
EXEC('CREATE INDEX ix_open_shifts_auto_due ON open_shifts (selection_mode, status, claim_deadline) WHERE auto_processed_at IS NULL');
