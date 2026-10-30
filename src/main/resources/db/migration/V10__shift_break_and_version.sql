-- TW-01: cột giờ nghỉ và khóa lạc quan của ca mà ShiftRepository đang dùng nhưng V8 chưa tạo. Không sửa V1-V9.
-- Chỉ thêm khi cột chưa có, để không lỗi trên database local đã được thêm cột bằng tay.

-- Giờ nghỉ trong ca (phút), bị trừ khỏi thời gian làm việc khi tính giới hạn giờ.
IF COL_LENGTH('shifts', 'break_minutes') IS NULL
    EXEC('ALTER TABLE shifts ADD break_minutes INT NOT NULL CONSTRAINT df_shifts_break_minutes DEFAULT 0');

IF OBJECT_ID('ck_shifts_break_minutes', 'C') IS NULL
    EXEC('ALTER TABLE shifts ADD CONSTRAINT ck_shifts_break_minutes CHECK (break_minutes >= 0)');

-- Phiên bản cho optimistic locking; mỗi lần sửa/hủy ca tăng 1.
IF COL_LENGTH('shifts', 'version') IS NULL
    EXEC('ALTER TABLE shifts ADD version INT NOT NULL CONSTRAINT df_shifts_version DEFAULT 1');

IF OBJECT_ID('ck_shifts_version', 'C') IS NULL
    EXEC('ALTER TABLE shifts ADD CONSTRAINT ck_shifts_version CHECK (version >= 1)');
