package org.dacnpm.schedulemanager.common;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

@RestController
@Profile("sqlserver")
public class WorkspaceDirectory {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditTrail audit;
    public WorkspaceDirectory(JdbcTemplate jdbc, PasswordEncoder passwords, AuditTrail audit) {
        this.jdbc = jdbc; this.passwords = passwords; this.audit = audit;
    }
    @GetMapping("/api/workspace/employees")
    public List<Map<String,Object>> employees() {
        CurrentUser.require().requireAdmin();
        return jdbc.queryForList("SELECT id,username,full_name AS fullName,employee_code AS employeeCode,level_id AS levelId,status,branch_id AS branchId,employee_type_id AS employeeTypeId,group_id AS groupId FROM users WHERE role='user' ORDER BY full_name");
    }

    public record EmployeeInput(String employeeCode,String username,String password,String fullName,String levelId,
                                String status,String branchId,String employeeTypeId,String groupId) {}
    @PostMapping("/api/workspace/employees")
    @Transactional
    public Map<String,String> create(@RequestBody EmployeeInput in) {
        CurrentUser actor=CurrentUser.require();actor.requireAdmin();validate(in);
        if(in.password()==null || in.password().length()<8)throw ApiException.badRequest("INVALID_PASSWORD","Mật khẩu cần ít nhất 8 ký tự.");
        String id=Ids.next("usr");
        try {
            jdbc.update("INSERT INTO users(id,employee_code,username,password_hash,full_name,role,level_id,status,branch_id,employee_type_id,group_id) VALUES(?,?,?,?,?,'user',?,?,?,?,?)",
                id,in.employeeCode(),in.username(),passwords.encode(in.password()),in.fullName(),in.levelId(),in.status(),in.branchId(),in.employeeTypeId(),in.groupId());
        } catch(DataIntegrityViolationException e){throw ApiException.conflict("EMPLOYEE_CONFLICT","Mã/tài khoản đã tồn tại hoặc danh mục không hợp lệ.");}
        audit.record("employee_create","Tạo nhân viên",id,actor.id(),null,Map.of("id",id,"fullName",in.fullName()),null);
        return Map.of("id",id);
    }
    @PutMapping("/api/workspace/employees/{id}")
    @Transactional
    public Map<String,String> update(@PathVariable String id,@RequestBody EmployeeInput in) {
        CurrentUser actor=CurrentUser.require();actor.requireAdmin();validate(in);
        var before=jdbc.queryForList("SELECT id,full_name,status,branch_id,employee_type_id,group_id FROM users WHERE id=? AND role='user'",id);
        if(before.isEmpty())throw ApiException.notFound("nhân viên");
        try { jdbc.update("UPDATE users SET employee_code=?,username=?,full_name=?,level_id=?,status=?,branch_id=?,employee_type_id=?,group_id=?,updated_at=SYSUTCDATETIME() WHERE id=? AND role='user'",
            in.employeeCode(),in.username(),in.fullName(),in.levelId(),in.status(),in.branchId(),in.employeeTypeId(),in.groupId(),id);
        } catch(DataIntegrityViolationException e){throw ApiException.conflict("EMPLOYEE_CONFLICT","Mã/tài khoản đã tồn tại hoặc danh mục không hợp lệ.");}
        audit.record("employee_update","Cập nhật nhân viên",id,actor.id(),before.get(0),Map.of("fullName",in.fullName(),"status",in.status()),null);
        return Map.of("id",id);
    }
    private static void validate(EmployeeInput in) {
        if(in.employeeCode()==null||in.employeeCode().isBlank()||in.employeeCode().length()>30
                ||in.username()==null||in.username().isBlank()||in.username().length()>30
                ||in.fullName()==null||in.fullName().isBlank()||in.fullName().length()>100
                ||!List.of("active","inactive").contains(in.status()==null?"":in.status()))
            throw ApiException.badRequest("INVALID_EMPLOYEE","Kiểm tra mã nhân viên, tài khoản, họ tên và trạng thái.");
    }
}
