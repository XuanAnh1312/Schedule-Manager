package org.dacnpm.schedulemanager.api.performance;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.dacnpm.schedulemanager.domain.events.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.dacnpm.schedulemanager.api.performance.PerformanceModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BAO-06: KPI, kỳ đánh giá, quyền xem/chấm, Part-time không bị trừ điểm vì ít giờ, đề xuất thưởng → Payroll 1 lần. */
class PerformanceServiceTest {

    static final CurrentUser ADMIN = new CurrentUser("admin", true);
    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final CurrentUser LAN = new CurrentUser("mgr_lan", false);
    static final CurrentUser HUNG = new CurrentUser("mgr_hung", false);
    static final LocalDate START = LocalDate.of(2026, 10, 1), END = LocalDate.of(2026, 10, 31);

    PerformanceRepository repo;
    DomainEventPublisher events;
    PerformanceService service;

    @BeforeEach
    void setUp() {
        repo = mock(PerformanceRepository.class);
        events = mock(DomainEventPublisher.class);
        service = new PerformanceService(repo, events, mock(AuditTrail.class));
    }

    /* ── KpiCalculator ─────────────────────────────────────────────── */

    @Test
    void partTimeWorkingTheirFullSchedule_scoresTheSameAsFullTime() {
        Metrics fullTime = new Metrics(20, 160 * 60, 20, 160 * 60, 0);
        Metrics partTime = new Metrics(10, 40 * 60, 10, 40 * 60, 0);

        assertEquals(KpiCalculator.compute(HOURS_VS_SCHEDULE, fullTime).value(),
                KpiCalculator.compute(HOURS_VS_SCHEDULE, partTime).value());
        assertEquals(0, new BigDecimal("100").compareTo(KpiCalculator.compute(HOURS_VS_SCHEDULE, partTime).value()));
        assertEquals(0, new BigDecimal("100").compareTo(KpiCalculator.compute(ATTENDANCE_RATE, partTime).value()));
    }

    @Test
    void noScheduleAtAll_isNotEnoughData_insteadOfAFakeZero() {
        Computed c = KpiCalculator.compute(ATTENDANCE_RATE, new Metrics(0, 0, 0, 0, 0));
        assertNull(c.value());
        assertTrue(c.basis().contains("chưa đủ dữ liệu"));
        assertEquals(0, new BigDecimal("12.50").compareTo(KpiCalculator.compute(ABSENCE_RATE, new Metrics(8, 0, 7, 0, 1)).value()));
    }

    @Test
    void weightedScore_ignoresUnscoredItems() {
        List<Item> items = List.of(item("i1", MANUAL, "4", "60"), item("i2", MANUAL, "5", "40"), item("i3", MANUAL, null, "10"));
        assertEquals(new BigDecimal("4.40"), KpiCalculator.weighted(items));
    }

    /* ── KPI ──────────────────────────────────────────────────────── */

    @Test
    void kpiValidation_andAdminOnly() {
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> service.createKpi(AN, kpiInput(MANUAL, HIGHER_BETTER, "10"))).getCode());
        assertEquals("INVALID_FORMULA", assertThrows(ApiException.class, () -> service.createKpi(ADMIN, kpiInput("magic", HIGHER_BETTER, "10"))).getCode());
        assertEquals("INVALID_DIRECTION", assertThrows(ApiException.class, () -> service.createKpi(ADMIN, kpiInput(ABSENCE_RATE, HIGHER_BETTER, "10"))).getCode());
        assertEquals("INVALID_WEIGHT", assertThrows(ApiException.class, () -> service.createKpi(ADMIN, kpiInput(MANUAL, HIGHER_BETTER, "0"))).getCode());
    }

    /* ── Mở kỳ ─────────────────────────────────────────────────────── */

    @Test
    void openCycle_createsAReviewPerEmployee_withTheDirectManagerAsReviewer_andDepartmentKpisOnly() {
        when(repo.cycle("c1")).thenReturn(Optional.of(new Cycle("c1", "Tháng 10", START, END, 5, "draft", 0, 0, 0)));
        when(repo.kpis(true)).thenReturn(List.of(kpi("k_all", ATTENDANCE_RATE, null), kpi("k_kho", MANUAL, "dep_kho")));
        when(repo.setCycleStatus("c1", 0, "draft", "open")).thenReturn(1);
        Map<String, Object> an = new HashMap<>(Map.of("id", "nv001", "departmentId", "dep_kho", "managerId", "mgr_lan"));
        Map<String, Object> binh = new HashMap<>(Map.of("id", "nv002", "departmentId", "dep_ban_hang"));
        binh.put("managerId", null);
        when(repo.activeEmployees()).thenReturn(List.of(an, binh));
        when(repo.metrics(anyString(), eq(START), eq(END))).thenReturn(new Metrics(10, 0, 9, 0, 1));

        service.openCycle(ADMIN, "c1", 0);

        verify(repo).insertReview(anyString(), eq("c1"), eq("nv001"), eq("mgr_lan"));
        verify(repo).insertReview(anyString(), eq("c1"), eq("nv002"), eq(null));
        verify(repo, times(3)).insertItem(anyString(), any(), any()); // An: 2 KPI, Bình: 1 KPI chung
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals(List.of("nv001", "nv002"), event.getValue().recipients());
    }

    @Test
    void openCycle_withoutKpis_isRejected() {
        when(repo.cycle("c1")).thenReturn(Optional.of(new Cycle("c1", "T10", START, END, 5, "draft", 0, 0, 0)));
        when(repo.kpis(true)).thenReturn(List.of());
        assertEquals("NO_KPI", assertThrows(ApiException.class, () -> service.openCycle(ADMIN, "c1", 0)).getCode());
        verify(repo, never()).setCycleStatus(anyString(), anyInt(), anyString(), anyString());
    }

    /* ── Quyền và luồng phiếu ─────────────────────────────────────── */

    private void stubReview(String status, List<Item> items) {
        Review r = review(status, items);
        List<Item> current = new ArrayList<>(items);
        when(repo.review("r1")).thenReturn(Optional.of(r));
        when(repo.items("r1")).thenAnswer(inv -> current);
        org.mockito.Mockito.doAnswer(inv -> {
            String id = inv.getArgument(0);
            BigDecimal self = inv.getArgument(2), mgr = inv.getArgument(3), actual = inv.getArgument(4);
            boolean setActual = inv.getArgument(5);
            for (int i = 0; i < current.size(); i++) {
                Item it = current.get(i);
                if (it.id().equals(id)) current.set(i, new Item(it.id(), it.reviewId(), it.kpiId(), it.kpiName(), it.unit(),
                        it.formulaType(), it.direction(), it.targetValue(), it.weight(), setActual ? actual : it.actualValue(),
                        it.actualBasis(), self != null ? self : it.selfScore(), mgr != null ? mgr : it.managerScore(), it.comment()));
            }
            return null;
        }).when(repo).scoreItem(anyString(), eq("r1"), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void employeeSeesOnlyOwnReview_managerOnlyTheirReviewees() {
        stubReview("pending_self", List.of(item("i1", MANUAL, null, "100")));

        assertEquals("r1", service.review(AN, "r1").id());
        assertEquals("r1", service.review(LAN, "r1").id());
        assertEquals("NOT_FOUND", assertThrows(ApiException.class, () -> service.review(HUNG, "r1")).getCode());
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service.review(new CurrentUser("nv002", false), "r1")).getCode());
    }

    @Test
    void selfReview_requiresEveryKpiScored_withinTheScale() {
        stubReview("pending_self", List.of(item("i1", MANUAL, null, "60"), item("i2", ATTENDANCE_RATE, null, "40")));

        assertEquals("INVALID_SCORE", assertThrows(ApiException.class, () -> service.submitSelf(AN, "r1",
                new SelfReviewInput(List.of(new ScoreInput("i1", new BigDecimal("6"), null, null)), null, 0))).getCode());
        assertEquals("SCORES_REQUIRED", assertThrows(ApiException.class, () -> service.submitSelf(AN, "r1",
                new SelfReviewInput(List.of(new ScoreInput("i1", new BigDecimal("4"), null, null)), null, 0))).getCode());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> service.submitSelf(LAN, "r1",
                new SelfReviewInput(List.of(), null, 0))).getCode());

        when(repo.submitSelf("r1", 0, "Cố gắng")).thenReturn(1);
        service.submitSelf(AN, "r1", new SelfReviewInput(List.of(new ScoreInput("i2", new BigDecimal("4.5"), null, null)),
                "Cố gắng", 0));
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals(List.of("mgr_lan"), event.getValue().recipients());
    }

    @Test
    void managerReview_onlyByTheReviewerOrAdmin_manualKpiNeedsAnActual_andTheOverallIsWeighted() {
        stubReview("pending_manager", List.of(item("i1", MANUAL, null, "60"), item("i2", ATTENDANCE_RATE, null, "40")));
        ManagerReviewInput scores = new ManagerReviewInput(List.of(new ScoreInput("i1", new BigDecimal("4"), null, null),
                new ScoreInput("i2", new BigDecimal("5"), null, null)), "Tốt", "Học thêm kỹ năng", 0);

        // Người ngoài không thấy phiếu (404, không lộ sự tồn tại), quản lý của phiếu mới chấm được.
        assertEquals("NOT_FOUND", assertThrows(ApiException.class, () -> service.submitManager(HUNG, "r1", scores)).getCode());
        assertEquals("ACTUAL_REQUIRED", assertThrows(ApiException.class, () -> service.submitManager(LAN, "r1", scores)).getCode());
        assertEquals("AUTO_KPI", assertThrows(ApiException.class, () -> service.submitManager(LAN, "r1",
                new ManagerReviewInput(List.of(new ScoreInput("i2", new BigDecimal("5"), new BigDecimal("99"), null)), null, null, 0))).getCode());

        when(repo.submitManager(eq("r1"), eq(0), any(), any(), any(), eq("mgr_lan"))).thenReturn(1);
        service.submitManager(LAN, "r1", new ManagerReviewInput(List.of(new ScoreInput("i1", new BigDecimal("4"),
                new BigDecimal("95"), null), new ScoreInput("i2", new BigDecimal("5"), null, null)), "Tốt", null, 0));

        verify(repo).submitManager(eq("r1"), eq(0), eq("Tốt"), eq(null), eq(new BigDecimal("4.40")), eq("mgr_lan"));
    }

    @Test
    void acknowledge_onlyByTheEmployee_whenPending() {
        stubReview("pending_ack", List.of(item("i1", MANUAL, "4", "100")));
        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> service.acknowledge(LAN, "r1", new AcknowledgeInput("ok", 0))).getCode());
        when(repo.acknowledge("r1", 0, "Đồng ý")).thenReturn(1);
        service.acknowledge(AN, "r1", new AcknowledgeInput("Đồng ý", 0));
        verify(repo).acknowledge("r1", 0, "Đồng ý");
    }

    /* ── Thành tích / đề xuất ─────────────────────────────────────── */

    @Test
    void bonusProposal_goesToPayrollAsOneDeterministicEvent_andNeedsAnAmount() {
        when(repo.isManagerOf("mgr_lan", "nv001")).thenReturn(true);
        when(repo.achievements("nv001")).thenAnswer(inv -> List.of(new Achievement(lastId[0], "nv001", "Trần Văn An",
                "bonus_proposal", "Vượt KPI", null, new BigDecimal("500000"), null, "mgr_lan", "Lan", null)));
        org.mockito.Mockito.doAnswer(inv -> { lastId[0] = inv.getArgument(0); return null; })
                .when(repo).insertAchievement(anyString(), any(), anyString());

        assertEquals("AMOUNT_REQUIRED", assertThrows(ApiException.class, () -> service.recordAchievement(LAN,
                new AchievementInput("nv001", "bonus_proposal", "Vượt KPI", null, null, null))).getCode());
        service.recordAchievement(LAN, new AchievementInput("nv001", "bonus_proposal", "Vượt KPI", null,
                new BigDecimal("500000"), null));

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertEquals("performance.bonus_proposed", event.getValue().type());
        assertEquals("performance.bonus_proposed:" + lastId[0] + ":v0", event.getValue().eventId());
        assertTrue(event.getValue().recipients().contains(DomainEvent.ROLE_ADMIN));
    }

    final String[] lastId = new String[1];

    @Test
    void achievements_areScopedToManagerAndSelf() {
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> service.recordAchievement(HUNG,
                new AchievementInput("nv001", "commendation", "Khen", null, null, null))).getCode());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> service.achievements(AN, "nv002")).getCode());
        service.achievements(AN, null);
        verify(repo).achievements("nv001");
    }

    /* ── Hỗ trợ ───────────────────────────────────────────────────── */

    static KpiInput kpiInput(String formula, String direction, String weight) {
        return new KpiInput("Tỷ lệ đi làm", null, "%", formula, direction, new BigDecimal("95"), new BigDecimal(weight),
                null, true, null);
    }

    static Kpi kpi(String id, String formula, String dept) {
        return new Kpi(id, id, null, "%", formula, HIGHER_BETTER, new BigDecimal("95"), new BigDecimal("50"), dept, true, 0);
    }

    static Item item(String id, String formula, String managerScore, String weight) {
        return new Item(id, "r1", "k_" + id, "KPI " + id, "%", formula, HIGHER_BETTER, new BigDecimal("95"),
                new BigDecimal(weight), null, null, null, managerScore == null ? null : new BigDecimal(managerScore), null);
    }

    static Review review(String status, List<Item> items) {
        return new Review("r1", "c1", "Tháng 10", START, END, 5, "nv001", "Trần Văn An", "mgr_lan", "Lan", status, null,
                null, null, null, null, null, null, null, 0, items);
    }
}
