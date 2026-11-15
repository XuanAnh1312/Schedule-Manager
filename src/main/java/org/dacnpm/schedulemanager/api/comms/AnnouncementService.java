package org.dacnpm.schedulemanager.api.comms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thông báo chung nội bộ (BAO-05): gửi theo phạm vi tổ chức, mẫu tin, lịch sử đọc; tùy chọn kèm email.
 *
 * <p>Gửi = ghi {@code announcements} + phát một sự kiện {@code announcement.published} vào outbox trong cùng giao
 * dịch. Outbox tạo thông báo in-app cho từng người (chống trùng theo event + người nhận) và, nếu có tick email, xếp
 * email vào hàng đợi riêng. Rollback → không có thông báo/email nào.
 */
@Profile("sqlserver")
@Service
public class AnnouncementService {

    public static final String EVENT_TYPE = EmailOutbox.ANNOUNCEMENT;
    static final Set<String> SCOPES = Set.of("company", "branch", "department", "group", "users");
    static final int MAX_TITLE = 200;
    static final int MAX_BODY = 4000;
    /** Nội dung thông báo in-app tối đa 1000 ký tự (domain_events.body). */
    static final int NOTIFICATION_BODY = 1000;

    public record SendInput(String title, String body, String scopeType, List<String> scopeIds, String templateId,
                            Boolean sendEmail, Boolean mandatory) {}

    public record TemplateInput(String name, String title, String body, Boolean active, Integer expectedVersion) {}

    private final AnnouncementRepository repository;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final ObjectMapper mapper;

    public AnnouncementService(AnnouncementRepository repository, DomainEventPublisher events, AuditTrail audit,
                               ObjectMapper mapper) {
        this.repository = repository;
        this.events = events;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public AnnouncementRepository.Announcement send(CurrentUser me, SendInput in) {
        me.requireAdmin();
        String title = text(in.title(), "Tiêu đề", MAX_TITLE);
        String body = text(in.body(), "Nội dung", MAX_BODY);
        String scope = in.scopeType() == null ? "" : in.scopeType().trim();
        if (!SCOPES.contains(scope)) {
            throw ApiException.badRequest("INVALID_SCOPE",
                    "Phạm vi phải là company, branch, department, group hoặc users.");
        }
        List<String> ids = in.scopeIds() == null ? List.of() : new LinkedHashSet<>(in.scopeIds().stream()
                .filter(x -> x != null && !x.isBlank()).map(String::trim).toList()).stream().toList();
        if (!"company".equals(scope) && ids.isEmpty()) {
            throw ApiException.badRequest("SCOPE_REQUIRED", "Chọn ít nhất một đơn vị/người nhận.");
        }
        if (ids.size() > 500) throw ApiException.badRequest("SCOPE_TOO_LARGE", "Tối đa 500 đơn vị/người nhận mỗi lần.");
        if (in.templateId() != null && !in.templateId().isBlank() && repository.template(in.templateId()).isEmpty()) {
            throw ApiException.badRequest("INVALID_TEMPLATE", "Mẫu tin không tồn tại.");
        }
        List<String> recipients = repository.recipients(scope, "company".equals(scope) ? List.of() : ids);
        if (recipients.isEmpty()) {
            throw ApiException.unprocessable("NO_RECIPIENTS", "Phạm vi đã chọn không có người nhận đang hoạt động.", List.of());
        }
        String id = Ids.next("ann");
        boolean email = Boolean.TRUE.equals(in.sendEmail());
        boolean mandatory = Boolean.TRUE.equals(in.mandatory());
        repository.insert(id, title, body, scope, json(ids), blank(in.templateId()), email, mandatory,
                recipients.size(), me.id());
        String preview = body.length() > NOTIFICATION_BODY ? body.substring(0, NOTIFICATION_BODY - 1) + "…" : body;
        events.publish(new DomainEvent(DomainEvent.idOf(EVENT_TYPE, id, 0), EVENT_TYPE, "announcement", id, 0,
                me.id(), recipients, title, preview, "announcements"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", id);
        after.put("scope", scope);
        after.put("scopeIds", ids);
        after.put("recipients", recipients.size());
        after.put("sendEmail", email);
        after.put("mandatory", mandatory);
        audit.record("announcement_send", "Gửi thông báo chung", title, me.id(), null, after, null);
        return repository.find(id).orElseThrow();
    }

    public List<AnnouncementRepository.Announcement> list(CurrentUser me) {
        me.requireAdmin();
        return repository.findAll(200);
    }

    public List<Map<String, Object>> mine(CurrentUser me) {
        return repository.findForRecipient(me.id(), 100);
    }

    /** Lịch sử đọc: Admin xem mọi thông báo. */
    public Map<String, Object> reads(CurrentUser me, String id) {
        me.requireAdmin();
        AnnouncementRepository.Announcement a = repository.find(id).orElseThrow(() -> ApiException.notFound("thông báo"));
        List<Map<String, Object>> rows = repository.reads(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("announcement", a);
        result.put("recipients", rows);
        result.put("readCount", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("isRead"))).count());
        result.put("deliveredCount", rows.size()); // outbox có thể chưa xử lý xong ngay sau khi gửi
        return result;
    }

    /* ── Mẫu tin ─────────────────────────────────────────────────────── */

    public List<AnnouncementRepository.Template> templates(CurrentUser me, boolean includeInactive) {
        me.requireAdmin();
        return repository.templates(!includeInactive);
    }

    @Transactional
    public AnnouncementRepository.Template createTemplate(CurrentUser me, TemplateInput in) {
        me.requireAdmin();
        String id = Ids.next("tpl");
        try {
            repository.insertTemplate(id, text(in.name(), "Tên mẫu", 100), text(in.title(), "Tiêu đề", MAX_TITLE),
                    text(in.body(), "Nội dung", MAX_BODY), me.id());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("TEMPLATE_EXISTS", "Đã có mẫu tin cùng tên.");
        }
        AnnouncementRepository.Template t = repository.template(id).orElseThrow();
        audit.record("announcement_template_create", "Tạo mẫu tin", t.name(), me.id(), null, t, null);
        return t;
    }

    @Transactional
    public AnnouncementRepository.Template updateTemplate(CurrentUser me, String id, TemplateInput in) {
        me.requireAdmin();
        AnnouncementRepository.Template before = repository.template(id).orElseThrow(() -> ApiException.notFound("mẫu tin"));
        int expected = in.expectedVersion() == null ? before.version() : in.expectedVersion();
        try {
            if (repository.updateTemplate(id, expected, text(in.name(), "Tên mẫu", 100), text(in.title(), "Tiêu đề", MAX_TITLE),
                    text(in.body(), "Nội dung", MAX_BODY), in.active() == null || in.active()) != 1) {
                throw ApiException.conflict("STALE_VERSION", "Mẫu tin vừa thay đổi, vui lòng tải lại.");
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("TEMPLATE_EXISTS", "Đã có mẫu tin cùng tên.");
        }
        AnnouncementRepository.Template after = repository.template(id).orElseThrow();
        audit.record("announcement_template_update", "Sửa mẫu tin", after.name(), me.id(), before, after, null);
        return after;
    }

    /* ── Hỗ trợ ─────────────────────────────────────────────────────── */

    static String text(String value, String field, int max) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("REQUIRED", field + " là bắt buộc.");
        String v = value.trim();
        if (v.length() > max) throw ApiException.badRequest("TOO_LONG", field + " tối đa " + max + " ký tự.");
        return v;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String json(List<String> ids) {
        try {
            return mapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
