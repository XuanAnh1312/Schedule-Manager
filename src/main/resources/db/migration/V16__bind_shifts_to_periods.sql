-- A date alone cannot identify a registration period when teams have overlapping periods.
ALTER TABLE shifts ADD period_id VARCHAR(64) NULL CONSTRAINT fk_shifts_period FOREIGN KEY REFERENCES periods(id);
EXEC('UPDATE s SET period_id=x.period_id FROM shifts s JOIN (SELECT pa.shift_id,MIN(p.period_id) AS period_id FROM plan_assignments pa JOIN schedule_plans p ON p.id=pa.plan_id GROUP BY pa.shift_id HAVING COUNT(DISTINCT p.period_id)=1) x ON x.shift_id=s.id');
EXEC('UPDATE s SET period_id=(SELECT MIN(p.id) FROM periods p WHERE s.shift_date BETWEEN p.start_date AND p.end_date) FROM shifts s WHERE s.period_id IS NULL AND (SELECT COUNT(*) FROM periods p WHERE s.shift_date BETWEEN p.start_date AND p.end_date)=1');
EXEC('CREATE INDEX ix_shifts_period_date ON shifts(period_id,shift_date)');
