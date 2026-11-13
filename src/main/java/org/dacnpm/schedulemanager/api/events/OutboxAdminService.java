package org.dacnpm.schedulemanager.api.events;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.OutboxDispatcher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vận hành outbox cho Admin (BAO-01): xem sự kiện gửi lỗi và gửi lại có kiểm soát.
 *
 * <ul>
 *   <li>Chỉ Admin; actor luôn lấy từ JWT.</li>
 *   <li>Chỉ gửi lại sự kiện {@code failed} hoặc {@code dead}; trạng thái khác trả 409.</li>
 *   <li>Bắt buộc lý do; mỗi lần gửi lại ghi audit trước/sau.</li>
 *   <li>Không tạo thông báo trùng: dispatcher dùng UNIQUE(event_id, recipient_id) + NOT EXISTS,
 *       người đã nhận ở lần thử trước không nhận thêm.</li>
 *   <li>Đặt lại trạng thái và audit nằm trong một giao dịch; việc gửi thử ngay chạy <b>sau commit</b>,
 *       lỗi khi gửi chỉ đưa sự kiện về {@code failed} như luồng tự động, không làm mất bản ghi audit.</li>
 * </ul>
 */
@Profile("sqlserver")
@Service
public class OutboxAdminService {

    static final List<String> ALL_STATUSES = List.of("pending", "processing", "processed", "failed", "dead");
    static final List<String> RETRYABLE = List.of("failed", "dead");
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_BULK_RETRY = 200;
    static final int MIN_REASON = 5;
    static final int MAX_REASON = 500;

    private final OutboxEventRepository repository;
    private final OutboxDispatcher dispatcher;
    private final AuditTrail audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OutboxAdminService(OutboxEventRepository repository,
                              OutboxDispatcher dispatcher,
                              AuditTrail audit,
                              TransactionTemplate tx,
                              Clock clock) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param status "problem" (mặc định: failed + dead), "all", hoặc một trạng thái cụ thể
     * @param from   ngày bắt đầu theo giờ nghiệp vụ (bao gồm)
     * @param to     ngày kết thúc theo giờ nghiệp vụ (bao gồm)
     */
    public OutboxEventPage list(CurrentUser user, String status, String type,
                                LocalDate from, LocalDate to, int page, int size) {
        user.requireAdmin();
        if (page < 0) throw ApiException.badRequest("INVALID_PAGE", "page phải ≥ 0.");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("INVALID_PAGE_SIZE", "size phải trong khoảng 1–" + MAX_PAGE_SIZE + ".");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "Từ ngày phải trước hoặc bằng đến ngày.");
        }
        OutboxEventFilter filter = new OutboxEventFilter(statuses(status), blankToNull(type),
                from == null ? null : startOfDayUtc(from), to == null ? null : startOfDayUtc(to.plusDays(1)));
        List<OutboxEventView> items = repository.find(filter, page * size, size);
        long total = repository.count(filter);
        return new OutboxEventPage(items, page, size, total, repository.countByStatus());
    }

    public Map<String, Long> summary(CurrentUser user) {
        user.requireAdmin();
        return repository.countByStatus();
    }

    /**
     * Gửi lại một sự kiện. Trả về trạng thái sau khi thử (hoặc {@code pending} nếu {@code dispatchNow = false}).
     */
    public OutboxEventView retry(CurrentUser user, String eventId, String reason, boolean dispatchNow) {
        user.requireAdmin();
        String why = requireReason(reason);
        tx.executeWithoutResult(status -> {
            OutboxEventView before = repository.lockById(eventId);
            if (before == null) {
                throw ApiException.notFound("sự kiện");
            }
            if (!RETRYABLE.contains(before.status())) {
                throw ApiException.conflict("EVENT_NOT_RETRYABLE",
                        "Chỉ gửi lại được sự kiện đang lỗi (failed/dead). Trạng thái hiện tại: " + before.status() + ".");
            }
            if (repository.resetForRetry(eventId, before.status()) != 1) {
                throw ApiException.conflict("EVENT_CHANGED", "Sự kiện vừa thay đổi trạng thái, vui lòng tải lại.");
            }
            audit.record("outbox_event_retry", "Gửi lại sự kiện thông báo", eventId, user.id(),
                    snapshot(before), Map.of("status", "pending", "attempts", 0), why);
        });
        if (dispatchNow) {
            dispatcher.dispatchOne(eventId);
        }
        return repository.findById(eventId);
    }

    /**
     * Gửi lại hàng loạt (ví dụ sau khi khắc phục sự cố hệ thống). Tối đa {@value #MAX_BULK_RETRY} sự kiện mỗi lần;
     * chỉ đưa về hàng đợi, dispatcher định kỳ sẽ gửi. Ghi một bản audit liệt kê các id.
     *
     * @param status "dead", "failed" hoặc "problem" (cả hai)
     */
    public Map<String, Object> retryAll(CurrentUser user, String status, String type, String reason) {
        user.requireAdmin();
        String why = requireReason(reason);
        List<String> statuses = "problem".equals(status) || status == null || status.isBlank()
                ? RETRYABLE : List.of(status);
        if (!RETRYABLE.containsAll(statuses)) {
            throw ApiException.badRequest("INVALID_STATUS", "Chỉ gửi lại hàng loạt sự kiện failed hoặc dead.");
        }
        String eventType = blankToNull(type);
        List<String> queued = tx.execute(txStatus -> {
            List<String> ids = repository.lockIdsForRetry(statuses, eventType, MAX_BULK_RETRY);
            List<String> done = new ArrayList<>();
            for (String id : ids) {
                OutboxEventView row = repository.findById(id);
                if (row != null && repository.resetForRetry(id, row.status()) == 1) {
                    done.add(id);
                }
            }
            if (!done.isEmpty()) {
                Map<String, Object> before = new LinkedHashMap<>();
                before.put("statuses", statuses);
                before.put("type", eventType);
                audit.record("outbox_event_retry_bulk", "Gửi lại hàng loạt sự kiện thông báo",
                        done.size() + " sự kiện", user.id(), before, Map.of("eventIds", done), why);
            }
            return done;
        });
        List<String> ids = queued == null ? List.of() : queued;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queued", ids.size());
        result.put("eventIds", ids);
        result.put("limit", MAX_BULK_RETRY);
        return result;
    }

    static List<String> statuses(String status) {
        if (status == null || status.isBlank() || "problem".equals(status)) {
            return RETRYABLE;
        }
        if ("all".equals(status)) {
            return List.of();
        }
        if (!ALL_STATUSES.contains(status)) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "status phải là problem, all hoặc một trong " + ALL_STATUSES + ".");
        }
        return List.of(status);
    }

    private LocalDateTime startOfDayUtc(LocalDate day) {
        // Ngày nghiệp vụ theo app.timezone → mốc UTC để so với occurred_at (lưu UTC).
        return day.atStartOfDay(clock.getZone()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_REASON || trimmed.length() > MAX_REASON) {
            throw ApiException.badRequest("REASON_REQUIRED",
                    "Cần nhập lý do gửi lại (" + MIN_REASON + "–" + MAX_REASON + " ký tự).");
        }
        return trimmed;
    }

    private static Map<String, Object> snapshot(OutboxEventView e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", e.status());
        m.put("attempts", e.attempts());
        m.put("lastError", e.lastError());
        return m;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
