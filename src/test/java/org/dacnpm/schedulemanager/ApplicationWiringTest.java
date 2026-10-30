package org.dacnpm.schedulemanager;

import static org.assertj.core.api.Assertions.assertThat;

import org.dacnpm.schedulemanager.schedule.calendar.CalendarService;
import org.dacnpm.schedulemanager.schedule.engine.ScheduleEngine;
import org.dacnpm.schedulemanager.schedule.events.ScheduleEventSink;
import org.dacnpm.schedulemanager.schedule.exception.ExceptionService;
import org.dacnpm.schedulemanager.schedule.official.OfficialScheduleService;
import org.dacnpm.schedulemanager.schedule.plan.PlanEditService;
import org.dacnpm.schedulemanager.schedule.plan.PlanService;
import org.dacnpm.schedulemanager.schedule.plan.PlanWorkflowService;
import org.dacnpm.schedulemanager.schedule.rules.EffectiveRulesSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Nạp toàn bộ ứng dụng với các bean của profile sqlserver (cơ sở dữ liệu là H2, Flyway tắt) để phát hiện lỗi ghép
 * bean: phụ thuộc vòng, thiếu bean, hai bean cùng loại. Không chạy câu SQL nghiệp vụ nào.
 */
@SpringBootTest
@ActiveProfiles({"sqlserver", "test"})
class ApplicationWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void scheduleModuleBeansAreWired() {
        for (Class<?> type : new Class<?>[] {ScheduleEngine.class, EffectiveRulesSource.class, PlanService.class,
                PlanWorkflowService.class, PlanEditService.class, ExceptionService.class, OfficialScheduleService.class,
                CalendarService.class, ScheduleEventSink.class}) {
            assertThat(context.getBeansOfType(type)).as(type.getSimpleName()).hasSize(1);
        }
    }
}
