package org.dacnpm.schedulemanager.api.comms;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin xem email gửi lỗi và gửi lại có lý do (BAO-05, cùng quy tắc với outbox BAO-01). */
@Profile("sqlserver")
@Service
public class EmailAdminService {

    static final List<String> PROBLEM = List.of("failed", "dead");
    static final List<String> STATUSES = List.of("pending", "sending", "sent", "failed", "dead", "skipped");

    public record Page(List<EmailRepository.EmailRow> items, long total, int page, int size,
                       Map<String, Long> statusCounts, boolean enabled, String transport) {}

    private final EmailRepository repository;
    private final EmailDispatcher dispatcher;
    private final MailSettings settings;
    private final AuditTrail audit;

    public EmailAdminService(EmailRepository repository, EmailDispatcher dispatcher, MailSettings settings,
                             AuditTrail audit) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.audit = audit;
    }

    public Page list(CurrentUser me, String status, Integer page, Integer size) {
        me.requireAdmin();
        List<String> filter = status == null || status.isBlank() || "problem".equals(status) ? PROBLEM
                : "all".equals(status) ? List.of() : List.of(status);
        if (!filter.isEmpty() && !STATUSES.containsAll(filter)) {
            throw ApiException.badRequest("INVALID_STATUS", "Trạng thái email không hợp lệ.");
        }
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 100);
        return new Page(repository.findPage(filter, p * s, s), repository.count(filter), p, s,
                repository.statusCounts(), settings.enabled(), settings.transport());
    }

    /** Đặt lại email failed/dead và (sau commit) gửi ngay. Lý do bắt buộc, có audit. */
    @Transactional
    public EmailRepository.EmailRow retry(CurrentUser me, String id, String reason) {
        me.requireAdmin();
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 5 || why.length() > 500) {
            throw ApiException.badRequest("REASON_REQUIRED", "Lý do gửi lại từ 5 đến 500 ký tự.");
        }
        repository.lock(id);
        EmailRepository.EmailRow before = repository.find(id).orElseThrow(() -> ApiException.notFound("email"));
        if (!PROBLEM.contains(before.status())) {
            throw ApiException.conflict("EMAIL_NOT_RETRYABLE", "Chỉ gửi lại được email đang lỗi (failed/dead).");
        }
        if (repository.resetForRetry(id) != 1) {
            throw ApiException.conflict("EMAIL_CHANGED", "Email vừa đổi trạng thái, vui lòng tải lại.");
        }
        audit.record("email_retry", "Gửi lại email", before.eventType() + " → " + before.recipientName(), me.id(),
                snapshot(before), Map.of("status", "pending", "attempts", 0), why);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Gửi sau commit: lỗi khi gửi không làm mất bản audit; email quay về failed như luồng tự động.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.sendOne(id);
                }
            });
        }
        return repository.find(id).orElseThrow();
    }

    private static Map<String, Object> snapshot(EmailRepository.EmailRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status());
        m.put("attempts", r.attempts());
        m.put("lastError", r.lastError());
        return m;
    }
}
