package org.dacnpm.schedulemanager.api.availability;

import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.DaySummary;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.EmployeeProgress;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Entry;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.MyAvailability;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Progress;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Registration;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.SaveRequest;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Slot;
import org.dacnpm.schedulemanager.api.availability.AvailabilityModels.Summary;
import org.dacnpm.schedulemanager.api.periods.PeriodRecord;
import org.dacnpm.schedulemanager.api.periods.PeriodRepository;
import org.dacnpm.schedulemanager.api.periods.PeriodStatus;
import org.dacnpm.schedulemanager.api.periods.PeriodView;
import org.dacnpm.schedulemanager.api.periods.RegistrationWindow;
import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.BusinessTime;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.common.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Đăng ký thời gian rảnh (TU-04) với hạn chót thực thi ở backend (TU-02, AC-04, AC-05).
 *
 * <p>Mỗi lần tạo/sửa/lưu nháp/gửi: khóa dòng kỳ, tính lại cửa sổ đăng ký bằng giờ server rồi mới
 * ghi, trong cùng một giao dịch. Form mở trước hạn nhưng gửi sau hạn vẫn bị từ chối.
 */
@Profile("sqlserver")
@Service
public class AvailabilityService {

    private static final int MAX_SLOTS_PER_DAY = 12;
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59);
    private static final Set<String> SLOT_TYPES =
            Set.of(AvailabilityModels.TYPE_AVAILABLE, AvailabilityModels.TYPE_UNAVAILABLE);

    private final AvailabilityRepository repository;
    private final PeriodRepository periods;
    private final AuditTrail audit;
    private final Clock clock;

    public AvailabilityService(AvailabilityRepository repository, PeriodRepository periods,
                               AuditTrail audit, Clock clock) {
        this.repository = repository;
        this.periods = periods;
        this.audit = audit;
        this.clock = clock;
    }

    /* ── Nhân viên ───────────────────────────────────────────────────────── */

    public MyAvailability getMine(CurrentUser me, String periodId) {
        requireEmployee(me);
        PeriodRecord period = visiblePeriod(periodId, false);
        requireApplicable(period, me);
        return myView(me, period);
    }

    /** Lưu (tạo mới thành nháp, hoặc sửa bản đang có — giữ nguyên trạng thái nháp/đã gửi). */
    @Transactional
    public MyAvailability saveMine(CurrentUser me, String periodId, SaveRequest request) {
        return write(me, periodId, request, false);
    }

    /** Gửi đăng ký: nội dung trên form được kiểm tra lại và lưu với trạng thái submitted. */
    @Transactional
    public MyAvailability submitMine(CurrentUser me, String periodId, SaveRequest request) {
        return write(me, periodId, request, true);
    }

    private MyAvailability write(CurrentUser me, String periodId, SaveRequest request, boolean submit) {
        requireEmployee(me);
        PeriodRecord period = visiblePeriod(periodId, true);
        requireApplicable(period, me);
        requireWindowOpen(period);

        List<Entry> entries = validateEntries(period, request);
        if (submit && entries.isEmpty()) {
            throw ApiException.badRequest("EMPTY_REGISTRATION", "Chưa có ngày nào được đăng ký.");
        }
        LocalDateTime nowUtc = BusinessTime.nowUtc(clock);
        AvailabilityRepository.RegistrationRow existing = repository.find(me.id(), periodId).orElse(null);
        String registrationId;
        if (existing == null) {
            registrationId = Ids.next("avail");
            repository.insert(registrationId, me.id(), periodId, submit ? "submitted" : "draft", submit ? nowUtc : null);
        } else {
            registrationId = existing.id();
            String status = submit ? "submitted" : existing.status();
            LocalDateTime submittedAt = submit ? nowUtc : existing.submittedAtUtc();
            repository.updateStatus(registrationId, status, submittedAt);
        }
        repository.replaceEntries(registrationId, entries);
        if (submit) {
            audit.record("availability.submitted", "Gửi đăng ký thời gian rảnh", period.name(), me.id(),
                    existing == null ? null : Map.of("status", existing.status()),
                    Map.of("periodId", periodId, "days", entries.size(), "status", "submitted"), null);
        }
        return myView(me, period);
    }

    /* ── Admin ───────────────────────────────────────────────────────────── */

    /** Ai đã gửi, ai đang nháp, ai chưa đăng ký. */
    public Progress progress(CurrentUser me, String periodId) {
        me.requireAdmin();
        PeriodRecord period = visiblePeriod(periodId, false);
        List<PeriodRepository.Employee> employees = periods.findApplicableEmployees(period.id());
        Map<String, AvailabilityRepository.RegistrationRow> byUser = repository.findByPeriod(period.id()).stream()
                .collect(Collectors.toMap(AvailabilityRepository.RegistrationRow::userId, Function.identity()));
        int submitted = 0;
        int draft = 0;
        List<EmployeeProgress> rows = new ArrayList<>();
        for (PeriodRepository.Employee e : employees) {
            AvailabilityRepository.RegistrationRow r = byUser.get(e.id());
            String status = r == null ? "none" : r.status();
            if ("submitted".equals(status)) submitted++;
            if ("draft".equals(status)) draft++;
            rows.add(new EmployeeProgress(e.id(), e.employeeCode(), e.fullName(), status,
                    r == null ? null : BusinessTime.toLocal(r.submittedAtUtc(), zone())));
        }
        return new Progress(period.id(), employees.size(), submitted, draft,
                employees.size() - submitted - draft, rows);
    }

    /** Khả năng đáp ứng theo ngày, chỉ tính đăng ký đã gửi của nhân viên còn thuộc đối tượng áp dụng. */
    public Summary summary(CurrentUser me, String periodId) {
        me.requireAdmin();
        PeriodRecord period = visiblePeriod(periodId, false);
        Set<String> applicable = periods.findApplicableEmployees(period.id()).stream()
                .map(PeriodRepository.Employee::id).collect(Collectors.toSet());
        Map<LocalDate, Set<String>> available = new HashMap<>();
        Map<LocalDate, Set<String>> unavailable = new HashMap<>();
        Map<LocalDate, Long> availableMinutes = new HashMap<>();
        Set<String> submittedUsers = new HashSet<>();
        for (AvailabilityRepository.SubmittedSlot s : repository.findSubmittedSlots(period.id())) {
            if (!applicable.contains(s.userId())) continue;
            submittedUsers.add(s.userId());
            if (AvailabilityModels.TYPE_AVAILABLE.equals(s.type())) {
                available.computeIfAbsent(s.date(), d -> new HashSet<>()).add(s.userId());
                availableMinutes.merge(s.date(), minutes(s.start(), s.end()), Long::sum);
            } else {
                unavailable.computeIfAbsent(s.date(), d -> new HashSet<>()).add(s.userId());
            }
        }
        List<DaySummary> days = new ArrayList<>();
        for (LocalDate d = period.startDate(); !d.isAfter(period.endDate()); d = d.plusDays(1)) {
            int dow = dayOfWeek(d);
            boolean exempt = period.exemptDates().contains(d);
            boolean closed = period.closedDates().contains(d);
            boolean required = period.requiredDaysOfWeek().contains(dow) && !exempt && !closed;
            days.add(new DaySummary(d, dow, required, exempt, closed,
                    available.getOrDefault(d, Set.of()).size(),
                    unavailable.getOrDefault(d, Set.of()).size(),
                    BigDecimal.valueOf(availableMinutes.getOrDefault(d, 0L))
                            .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)));
        }
        return new Summary(period.id(), applicable.size(), submittedUsers.size(), days);
    }

    /** Đăng ký đã gửi kèm chi tiết — nguồn dữ liệu cho phân lịch. */
    public List<Registration> submitted(CurrentUser me, String periodId) {
        me.requireAdmin();
        PeriodRecord period = visiblePeriod(periodId, false);
        return repository.findByPeriod(period.id()).stream()
                .filter(r -> "submitted".equals(r.status()))
                .map(r -> toRegistration(r, repository.loadEntries(r.id())))
                .toList();
    }

    /**
     * Gọi nội bộ từ module lịch (không qua HTTP, không cần token): đăng ký đã gửi của nhân viên
     * còn hoạt động và còn thuộc đối tượng áp dụng của kỳ. Kỳ không tồn tại → 404.
     */
    public List<Registration> submittedForScheduling(String periodId) {
        PeriodRecord period = periods.findById(periodId).orElseThrow(() -> ApiException.notFound("kỳ đăng ký"));
        Set<String> applicable = periods.findApplicableEmployees(period.id()).stream()
                .map(PeriodRepository.Employee::id).collect(Collectors.toSet());
        return repository.findByPeriod(period.id()).stream()
                .filter(r -> "submitted".equals(r.status()) && applicable.contains(r.userId()))
                .map(r -> toRegistration(r, repository.loadEntries(r.id())))
                .toList();
    }

    /* ── Kiểm tra ────────────────────────────────────────────────────────── */

    private void requireWindowOpen(PeriodRecord period) {
        RegistrationWindow.State state = RegistrationWindow.evaluate(period, clock.instant());
        if (state == RegistrationWindow.State.OPEN) {
            return;
        }
        String label = RegistrationWindow.label(period, state, zone());
        String code = state == RegistrationWindow.State.UPCOMING ? "REGISTRATION_NOT_OPEN" : "REGISTRATION_CLOSED";
        throw ApiException.conflict(code, label);
    }

    /**
     * Ngày hợp lệ: trong kỳ, thuộc thứ cần đăng ký, không phải ngày miễn/đóng, không trùng.
     * Khoảng giờ: start &lt; end, loại hợp lệ, không chồng nhau trong cùng ngày.
     */
    List<Entry> validateEntries(PeriodRecord period, SaveRequest request) {
        if (request == null || request.entries() == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Thiếu danh sách ngày đăng ký (entries).");
        }
        List<String> errors = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        List<Entry> result = new ArrayList<>();
        for (Entry entry : request.entries()) {
            if (entry == null || entry.date() == null) {
                errors.add("entries: thiếu ngày.");
                continue;
            }
            LocalDate date = entry.date();
            if (!seen.add(date)) {
                errors.add(date + ": ngày bị lặp.");
                continue;
            }
            if (entry.slots() == null || entry.slots().isEmpty()) {
                continue; // ngày không có khoảng giờ = bỏ đăng ký ngày đó
            }
            if (date.isBefore(period.startDate()) || date.isAfter(period.endDate())) {
                errors.add(date + ": nằm ngoài kỳ.");
                continue;
            }
            if (period.closedDates().contains(date)) {
                errors.add(date + ": ngày đóng, không nhận đăng ký.");
                continue;
            }
            if (period.exemptDates().contains(date)) {
                errors.add(date + ": ngày được miễn, không cần đăng ký.");
                continue;
            }
            if (!period.requiredDaysOfWeek().contains(dayOfWeek(date))) {
                errors.add(date + ": không thuộc các thứ cần đăng ký của kỳ.");
                continue;
            }
            List<Slot> slots = validateSlots(date, entry.slots(), errors);
            if (slots != null) {
                result.add(new Entry(date, slots));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu đăng ký không hợp lệ.", errors);
        }
        result.sort(Comparator.comparing(Entry::date));
        return result;
    }

    private static List<Slot> validateSlots(LocalDate date, List<Slot> input, List<String> errors) {
        if (input.size() > MAX_SLOTS_PER_DAY) {
            errors.add(date + ": tối đa " + MAX_SLOTS_PER_DAY + " khoảng giờ mỗi ngày.");
            return null;
        }
        List<Slot> slots = new ArrayList<>();
        boolean ok = true;
        for (Slot s : input) {
            if (s == null || s.start() == null || s.end() == null) {
                errors.add(date + ": khoảng giờ thiếu giờ bắt đầu/kết thúc.");
                ok = false;
                continue;
            }
            String type = s.type() == null ? AvailabilityModels.TYPE_AVAILABLE : s.type().trim().toLowerCase(Locale.ROOT);
            if (!SLOT_TYPES.contains(type)) {
                errors.add(date + ": loại khoảng giờ phải là available hoặc unavailable.");
                ok = false;
                continue;
            }
            LocalTime start = s.start().withSecond(0).withNano(0);
            LocalTime end = s.end().withSecond(0).withNano(0);
            if (!start.isBefore(end)) {
                errors.add(date + ": giờ bắt đầu " + start + " phải trước giờ kết thúc " + end + ".");
                ok = false;
                continue;
            }
            slots.add(new Slot(start, end, type));
        }
        slots.sort(Comparator.comparing(Slot::start));
        for (int i = 1; i < slots.size(); i++) {
            if (slots.get(i).start().isBefore(slots.get(i - 1).end())) {
                errors.add(date + ": khoảng " + slots.get(i - 1).start() + "–" + slots.get(i - 1).end()
                        + " chồng lên " + slots.get(i).start() + "–" + slots.get(i).end() + ".");
                ok = false;
            }
        }
        return ok ? List.copyOf(slots) : null;
    }

    private void requireEmployee(CurrentUser me) {
        if (me.admin()) {
            throw ApiException.forbidden("Chỉ nhân viên mới đăng ký thời gian rảnh.");
        }
    }

    private void requireApplicable(PeriodRecord period, CurrentUser me) {
        if (!periods.isApplicableActiveEmployee(period.id(), me.id())) {
            throw ApiException.forbidden("Kỳ đăng ký này không áp dụng cho bạn hoặc tài khoản không hoạt động.");
        }
    }

    /** Kỳ nháp không tồn tại với người ngoài admin; {@code forUpdate} giữ khóa dòng kỳ tới hết giao dịch. */
    private PeriodRecord visiblePeriod(String periodId, boolean forUpdate) {
        if (periodId == null || periodId.isBlank()) {
            throw ApiException.badRequest("PERIOD_REQUIRED", "Thiếu periodId.");
        }
        PeriodRecord period = (forUpdate ? periods.findByIdForUpdate(periodId) : periods.findById(periodId))
                .orElseThrow(() -> ApiException.notFound("kỳ đăng ký"));
        if (period.status() == PeriodStatus.DRAFT) {
            throw ApiException.notFound("kỳ đăng ký");
        }
        return period;
    }

    private MyAvailability myView(CurrentUser me, PeriodRecord period) {
        Registration registration = repository.find(me.id(), period.id())
                .map(r -> toRegistration(r, repository.loadEntries(r.id())))
                .orElse(new Registration(null, period.id(), me.id(), "none", null, null, List.of()));
        RegistrationWindow.State state = RegistrationWindow.evaluate(period, clock.instant());
        return new MyAvailability(PeriodView.of(period, clock.instant(), zone(), false), registration,
                state == RegistrationWindow.State.OPEN, RegistrationWindow.label(period, state, zone()));
    }

    private Registration toRegistration(AvailabilityRepository.RegistrationRow r, List<Entry> entries) {
        return new Registration(r.id(), r.periodId(), r.userId(), r.status(),
                BusinessTime.toLocal(r.submittedAtUtc(), zone()),
                BusinessTime.toLocal(r.updatedAtUtc(), zone()), entries);
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    /** 0 = Chủ nhật … 6 = Thứ bảy, khớp {@code period_weekdays} và SPA. */
    static int dayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    /** "23:59" được tính như hết ngày (24:00) để "cả ngày" = 24 giờ. */
    static long minutes(LocalTime start, LocalTime end) {
        long m = Duration.between(start, end).toMinutes();
        return END_OF_DAY.equals(end) ? m + 1 : m;
    }
}
