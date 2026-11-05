-- Explicit synthetic data for the five requested accounts; never a Flyway migration.
USE ScheduleManager;
SET NOCOUNT ON;
SET XACT_ABORT ON;
IF DB_NAME()<>N'ScheduleManager' THROW 51000, 'Wrong local database.', 1;
DECLARE @fixture TABLE(username VARCHAR(30) PRIMARY KEY,target_rate DECIMAL(5,2),max_week DECIMAL(5,2));
INSERT INTO @fixture VALUES
 ('anh.npxa@hrm.com',100,40),('tuong.vdt@hrm.com',100,40),('tuan.ntt@hrm.com',100,40),
 ('binh.ncb@hrm.com',60,24),('bao.dlhb@hrm.com',50,20);
IF (SELECT COUNT(*) FROM dbo.users u JOIN @fixture f ON f.username=u.username WHERE u.role='user' AND u.status='active' AND u.archived_at IS NULL)<>5
    THROW 51001, 'All five active Employee accounts must exist before filling contract fields.', 1;
DECLARE @actor VARCHAR(64)=(SELECT id FROM dbo.users WHERE username='admin' AND role='admin' AND status='active');
IF @actor IS NULL THROW 51002, 'Expected local Admin account is missing.', 1;
DECLARE @changes TABLE(id VARCHAR(64),username VARCHAR(30),old_rate DECIMAL(5,2),new_rate DECIMAL(5,2),old_week DECIMAL(5,2),new_week DECIMAL(5,2),old_version INT,new_version INT);
BEGIN TRANSACTION;
UPDATE u SET target_rate=f.target_rate,contract_max_hours_per_week=f.max_week,version=u.version+1,updated_at=SYSUTCDATETIME()
OUTPUT inserted.id,inserted.username,deleted.target_rate,inserted.target_rate,deleted.contract_max_hours_per_week,inserted.contract_max_hours_per_week,deleted.version,inserted.version INTO @changes
FROM dbo.users u JOIN @fixture f ON u.username=f.username
WHERE u.role='user' AND (u.target_rate<>f.target_rate OR u.contract_max_hours_per_week IS NULL OR u.contract_max_hours_per_week<>f.max_week);
INSERT INTO dbo.history(id,event_type,title,detail,actor_id,actor_name,before_json,after_json,reason)
SELECT 'hist_fixture_'+REPLACE(CONVERT(VARCHAR(36),NEWID()),'-',''),'employee_test_profile',N'Bổ sung thông số hồ sơ test',c.username,@actor,
       (SELECT full_name FROM dbo.users WHERE id=@actor),
       (SELECT c.old_rate AS targetRate,c.old_week AS contractMaxHoursPerWeek,c.old_version AS version FOR JSON PATH,WITHOUT_ARRAY_WRAPPER,INCLUDE_NULL_VALUES),
       (SELECT c.new_rate AS targetRate,c.new_week AS contractMaxHoursPerWeek,c.new_version AS version FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),
       N'Dữ liệu giả lập theo yêu cầu: điền toàn bộ hồ sơ 5 tài khoản test; không phải chính sách lao động thực tế.'
FROM @changes c;
COMMIT;
SELECT u.username,u.full_name,u.phone,u.email,u.join_date,u.target_rate,u.contract_max_hours_per_week,u.status
FROM dbo.users u JOIN @fixture f ON u.username=f.username ORDER BY u.username;
SELECT COUNT(*) AS remaining_moca_test_databases FROM sys.databases WHERE name LIKE N'ScheduleManager[_]MocA[_]Test%';
