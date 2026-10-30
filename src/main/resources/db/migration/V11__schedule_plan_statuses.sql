-- TW-04: trạng thái phương án lịch theo hợp đồng đã chốt với module kỳ (Docs/api.md, mục "Hợp đồng với phần lịch"):
-- draft -> pending_approval -> approved -> published; bản cũ khi công bố bản mới là superseded; hủy là cancelled.
-- Mức đủ người (full/understaffed) là chỉ số riêng, không phải trạng thái. Không sửa V6.

ALTER TABLE schedule_plans DROP CONSTRAINT ck_schedule_plans_status;

UPDATE schedule_plans SET status = 'published' WHERE status = 'applied';
UPDATE schedule_plans SET status = 'cancelled' WHERE status = 'discarded';

ALTER TABLE schedule_plans ADD CONSTRAINT ck_schedule_plans_status
    CHECK (status IN ('draft', 'pending_approval', 'approved', 'published', 'superseded', 'cancelled'));

CREATE INDEX ix_schedule_plans_period_status ON schedule_plans (period_id, status);
