-- LOCAL QA ONLY: adds two dummy employees and one published assigned shift.
-- The seeded QA users deliberately share the documented local-development BCrypt hash.
-- DO NOT run against production or commit real employee credentials.
USE ScheduleManager;
GO
IF NOT EXISTS (SELECT 1 FROM users WHERE id='usr_qa_001')
 INSERT INTO users(id,employee_code,username,password_hash,full_name,role,level_id,status,target_rate)
 VALUES ('usr_qa_001','QA001','smqa001',
 '$2a$10$rQUEC.jn9yzcg93LB3O4zugWOYJk.rVvweKiHgHOBe3CA.Q74BfSG',N'Nhân viên QA 1','user','lvl1','active',100);
IF NOT EXISTS (SELECT 1 FROM users WHERE id='usr_qa_002')
 INSERT INTO users(id,employee_code,username,password_hash,full_name,role,level_id,status,target_rate)
 VALUES ('usr_qa_002','QA002','smqa002',
 '$2a$10$rQUEC.jn9yzcg93LB3O4zugWOYJk.rVvweKiHgHOBe3CA.Q74BfSG',N'Nhân viên QA 2','user','lvl1','active',100);
IF NOT EXISTS (SELECT 1 FROM periods WHERE id='qa_attendance_period')
 INSERT INTO periods(id,name,cycle,start_date,end_date,status)
 VALUES ('qa_attendance_period',N'Kỳ QA Chấm công','monthly',DATEADD(day,-1,CAST(GETDATE() AS DATE)),
 DATEADD(day,1,CAST(GETDATE() AS DATE)),'open');
ELSE UPDATE periods SET start_date=DATEADD(day,-1,CAST(GETDATE() AS DATE)),
 end_date=DATEADD(day,1,CAST(GETDATE() AS DATE)) WHERE id='qa_attendance_period';
IF NOT EXISTS (SELECT 1 FROM shifts WHERE id='qa_shift_today')
 INSERT INTO shifts(id,name,shift_date,start_time,end_time,assignment_mode,total_required,status,note)
 VALUES ('qa_shift_today',N'Ca kiểm thử SQL',CAST(GETDATE() AS DATE),CAST(GETDATE() AS TIME(0)),
 CAST(DATEADD(hour,3,GETDATE()) AS TIME(0)),'user',2,'published',N'Ca QA 2 người');
ELSE UPDATE shifts SET shift_date=CAST(GETDATE() AS DATE),start_time=CAST(GETDATE() AS TIME(0)),
 end_time=CAST(DATEADD(hour,3,GETDATE()) AS TIME(0)),status='published' WHERE id='qa_shift_today';
IF NOT EXISTS(SELECT 1 FROM assignments WHERE shift_id='qa_shift_today' AND user_id='usr_qa_001')
 INSERT INTO assignments(id,shift_id,user_id,assigned_by,status)
 VALUES ('qa_assignment_001','qa_shift_today','usr_qa_001','usr_admin_001','confirmed');
IF NOT EXISTS(SELECT 1 FROM assignments WHERE shift_id='qa_shift_today' AND user_id='usr_qa_002')
 INSERT INTO assignments(id,shift_id,user_id,assigned_by,status)
 VALUES ('qa_assignment_002','qa_shift_today','usr_qa_002','usr_admin_001','confirmed');
SELECT id,name,shift_date,start_time,end_time,status FROM shifts WHERE id='qa_shift_today';
GO
