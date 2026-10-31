-- Trần giờ làm theo tuần: giờ mục tiêu 60 giờ/tuần x target_rate 125% = 75 giờ.
-- max_hours_per_week (chính sách) phải >= 75, nếu không trần thực tế vẫn bị giới hạn ở 48 giờ.
-- max_hours_per_period (80) không được đổi ở đây; xem ghi chú bàn giao về trần theo kỳ.
UPDATE schedule_rules
SET max_hours_per_week = 75,
    target_hours_per_week = 60,
    updated_at = SYSUTCDATETIME()
WHERE id = 'rules_main';
