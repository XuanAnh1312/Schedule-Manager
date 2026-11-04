package org.dacnpm.schedulemanager.common;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Admin-only V1 catalog maintenance and immutable audit lookup. */
@RestController
@Profile("sqlserver")
public class FoundationController {
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;
    public FoundationController(JdbcTemplate jdbc, AuditTrail audit) { this.jdbc=jdbc; this.audit=audit; }

    @GetMapping("/api/audit")
    public Map<String,Object> audit(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="30") int size, @RequestParam(defaultValue="") String eventType) {
        CurrentUser.require().requireAdmin();
        if(page<0||page>100000||size<1||size>100)throw ApiException.badRequest("INVALID_PAGE","Trang hoặc số dòng không hợp lệ.");
        String filter=" WHERE (?='' OR event_type=?)";
        return Map.of("items",jdbc.queryForList("SELECT id,event_type AS eventType,title,detail,actor_id AS actorId,actor_name AS actorName,occurred_at AS occurredAt,before_json AS beforeJson,after_json AS afterJson,reason FROM history"+filter+" ORDER BY occurred_at DESC,id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",eventType,eventType,page*size,size),
            "total",jdbc.queryForObject("SELECT COUNT(*) FROM history"+filter,Integer.class,eventType,eventType),"page",page,"size",size);
    }

    public record LevelInput(String name,String description,String color,Integer order) {}
    @PostMapping("/api/workspace/levels") @Transactional
    public Map<String,String> createLevel(@RequestBody LevelInput in) {
        var actor=CurrentUser.require();actor.requireAdmin();validate(in);String id=Ids.next("lvl");
        jdbc.update("INSERT INTO levels(id,name,description,color,sort_order) VALUES(?,?,?,?,?)",id,in.name(),in.description(),in.color(),in.order());
        audit.record("level_create","Tạo Level",id,actor.id(),null,in,null);return Map.of("id",id);
    }
    @PutMapping("/api/workspace/levels/{id}") @Transactional
    public Map<String,String> updateLevel(@PathVariable String id,@RequestBody LevelInput in) {
        var actor=CurrentUser.require();actor.requireAdmin();validate(in);
        var before=jdbc.queryForList("SELECT * FROM levels WHERE id=?",id);
        if(before.isEmpty())throw ApiException.notFound("Level");
        jdbc.update("UPDATE levels SET name=?,description=?,color=?,sort_order=? WHERE id=?",in.name(),in.description(),in.color(),in.order(),id);
        audit.record("level_update","Sửa Level",id,actor.id(),before.get(0),in,null);return Map.of("id",id);
    }
    @DeleteMapping("/api/workspace/levels/{id}") @Transactional
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteLevel(@PathVariable String id) {
        var actor=CurrentUser.require();actor.requireAdmin();var before=jdbc.queryForList("SELECT * FROM levels WHERE id=?",id);
        if(before.isEmpty())throw ApiException.notFound("Level");
        jdbc.update("DELETE FROM levels WHERE id=?",id);
        audit.record("level_delete","Xóa Level chưa sử dụng",id,actor.id(),before.get(0),null,null);
    }
    private static void validate(LevelInput in) {
        if(in.name()==null||in.name().isBlank()||in.name().length()>100||in.color()==null||!in.color().matches("#[0-9A-Fa-f]{6}")||in.order()==null||in.order()<0
            ||(in.description()!=null&&in.description().length()>500))throw ApiException.badRequest("INVALID_LEVEL","Kiểm tra tên, màu #RRGGBB, thứ tự và mô tả Level.");
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> conflict() {
        return org.springframework.http.ResponseEntity.status(409).body(Map.of("code","CATALOG_CONFLICT","error","Tên/thứ tự đã tồn tại hoặc danh mục đang được sử dụng."));
    }
}
