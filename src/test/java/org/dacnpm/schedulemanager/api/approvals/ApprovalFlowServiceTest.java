package org.dacnpm.schedulemanager.api.approvals;

import org.dacnpm.schedulemanager.common.ApiException;
import org.dacnpm.schedulemanager.common.AuditTrail;
import org.dacnpm.schedulemanager.common.CurrentUser;
import org.dacnpm.schedulemanager.domain.events.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** BAO-04: bước duyệt theo quản lý trực tiếp. */
class ApprovalFlowServiceTest {

    static final CurrentUser AN = new CurrentUser("nv001", false);
    static final CurrentUser LAN = new CurrentUser("mgr_lan", false);      // quản lý của An
    static final CurrentUser HUNG = new CurrentUser("mgr_hung", false);    // quản lý nhóm khác
    static final CurrentUser ADMIN = new CurrentUser("admin", true);

    FakeApprovalRepository repo;
    ApprovalFlowService flows;

    @BeforeEach
    void setUp() {
        repo = new FakeApprovalRepository();
        repo.managers.put("nv001", "mgr_lan");
        repo.managers.put("nv009", "mgr_hung");
        flows = new ApprovalFlowService(repo, mock(AuditTrail.class));
    }

    @Test
    void adminOnly_createsNoSteps_andOnlyAdminCanApprove() {
        repo.mode("leave", null, ApprovalFlow.ADMIN_ONLY);

        assertEquals(List.of(DomainEvent.ROLE_ADMIN), flows.start("leave", "l1", "nv001", "leave_annual"));
        assertTrue(repo.steps.isEmpty());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> flows.approve(LAN, "leave", "l1", null)).getCode());
        assertEquals(ApprovalFlowService.Outcome.FINAL, flows.approve(ADMIN, "leave", "l1", null).outcome());
    }

    @Test
    void twoSteps_managerThenAdmin_theScheduleChangesOnlyAtTheLastStep() {
        repo.mode("leave", null, ApprovalFlow.MANAGER_THEN_ADMIN);

        assertEquals(List.of("mgr_lan"), flows.start("leave", "l1", "nv001", "leave_annual"));
        ApprovalFlowService.Decision first = flows.approve(LAN, "leave", "l1", "OK");
        assertEquals(ApprovalFlowService.Outcome.STEP_APPROVED, first.outcome());
        assertEquals(List.of(DomainEvent.ROLE_ADMIN), first.nextRecipients());
        // Quản lý không duyệt thay bước của Admin.
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> flows.approve(LAN, "leave", "l1", null)).getCode());

        assertEquals(ApprovalFlowService.Outcome.FINAL, flows.approve(ADMIN, "leave", "l1", null).outcome());
        assertTrue(repo.findSteps("leave", "l1").stream().noneMatch(ApprovalStep::open));
    }

    @Test
    void managerOutsideScope_cannotApprove_orSeeTheRequest() {
        repo.mode("leave", null, ApprovalFlow.MANAGER);
        flows.start("leave", "l1", "nv001", null);

        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> flows.approve(HUNG, "leave", "l1", null)).getCode());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> flows.reject(HUNG, "leave", "l1", "x")).getCode());
        assertFalse(flows.canView(HUNG, "leave", "l1", "nv001"));
        assertTrue(flows.canView(LAN, "leave", "l1", "nv001"));
        assertTrue(flows.canDecide(LAN, "leave", "l1"));
        assertFalse(flows.canDecide(HUNG, "leave", "l1"));
    }

    @Test
    void oneStepManagerMode_managerApprovalIsFinal() {
        repo.mode("shift_request", null, ApprovalFlow.MANAGER);
        flows.start("shift_request", "r1", "nv001", null);

        assertEquals(ApprovalFlowService.Outcome.FINAL, flows.approve(LAN, "shift_request", "r1", null).outcome());
    }

    @Test
    void employeeWithoutManager_goesStraightToAdmin_withARecordedReason() {
        repo.mode("leave", null, ApprovalFlow.MANAGER);

        assertEquals(List.of(DomainEvent.ROLE_ADMIN), flows.start("leave", "l2", "nv_no_manager", null));
        List<ApprovalStep> steps = repo.findSteps("leave", "l2");
        assertEquals("skipped", steps.get(0).status());
        assertTrue(steps.get(0).note().contains("Không có quản lý"));
        assertEquals("pending", steps.get(1).status());
        assertEquals(ApprovalStep.ADMIN, steps.get(1).approverKind());
    }

    @Test
    void adminAtTheManagerStep_isFinal_andSkipsTheRest() {
        repo.mode("leave", null, ApprovalFlow.MANAGER_THEN_ADMIN);
        flows.start("leave", "l1", "nv001", null);

        assertEquals(ApprovalFlowService.Outcome.FINAL, flows.approve(ADMIN, "leave", "l1", null).outcome());
        List<ApprovalStep> steps = repo.findSteps("leave", "l1");
        assertEquals("approved", steps.get(0).status());
        assertEquals("skipped", steps.get(1).status());
    }

    @Test
    void rejectAtTheManagerStep_closesTheRemainingSteps() {
        repo.mode("leave", null, ApprovalFlow.MANAGER_THEN_ADMIN);
        flows.start("leave", "l1", "nv001", null);

        flows.reject(LAN, "leave", "l1", "Thiếu người");

        List<ApprovalStep> steps = repo.findSteps("leave", "l1");
        assertEquals("rejected", steps.get(0).status());
        assertEquals("skipped", steps.get(1).status());
    }

    @Test
    void perLeaveTypeFlow_overridesTheDefault() {
        repo.mode("leave", null, ApprovalFlow.ADMIN_ONLY);
        repo.mode("leave", "leave_sick", ApprovalFlow.MANAGER);

        assertEquals(List.of("mgr_lan"), flows.start("leave", "l1", "nv001", "leave_sick"));
        assertEquals(List.of(DomainEvent.ROLE_ADMIN), flows.start("leave", "l2", "nv001", "leave_annual"));
    }

    @Test
    void setFlow_validatesInput_andIsAdminOnly() {
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> flows.setFlow(AN,
                new ApprovalFlowService.FlowInput("leave", null, "manager", null))).getCode());
        assertEquals("INVALID_MODE", assertThrows(ApiException.class, () -> flows.setFlow(ADMIN,
                new ApprovalFlowService.FlowInput("leave", null, "everyone", null))).getCode());
        assertEquals("INVALID_SCOPE", assertThrows(ApiException.class, () -> flows.setFlow(ADMIN,
                new ApprovalFlowService.FlowInput("shift_request", "leave_sick", "manager", null))).getCode());

        ApprovalFlow saved = flows.setFlow(ADMIN, new ApprovalFlowService.FlowInput("leave", null, "manager", null));
        assertEquals("manager", saved.mode());
        assertEquals("STALE_VERSION", assertThrows(ApiException.class, () -> flows.setFlow(ADMIN,
                new ApprovalFlowService.FlowInput("leave", null, "admin_only", 99))).getCode());
    }

    @Test
    void stepAlreadyDecidedByAnotherApprover_isAConflict() {
        repo.mode("leave", null, ApprovalFlow.MANAGER_THEN_ADMIN);
        flows.start("leave", "l1", "nv001", null);
        ApprovalStep stale = repo.findSteps("leave", "l1").get(0);
        flows.approve(LAN, "leave", "l1", null);

        // Bước 1 đã duyệt; một lượt duyệt dùng dữ liệu cũ không được ghi đè.
        assertEquals(0, repo.decideStep(stale.id(), stale.version(), "pending", "approved", "mgr_lan", null));
    }
}
