package org.dacnpm.schedulemanager.schedule.model;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserAvailabilityTest {
    private final LocalDate date=LocalDate.of(2026,11,2);
    @Test void unavailableWinsOverAnOverlappingAvailableWindow() {
        var result=UserAvailability.subtract("u",true,Map.of(date,List.of(new TimeRange(480,1020))),
                Map.of(date,List.of(new TimeRange(600,720))));
        assertThat(result.slotsByDate().get(date)).containsExactly(new TimeRange(480,600),new TimeRange(720,1020));
    }
    @Test void overnightUnavailabilityAlsoBlocksTheFollowingDay() {
        var result=UserAvailability.subtract("u",true,Map.of(date.plusDays(1),List.of(new TimeRange(0,480))),
                Map.of(date,List.of(new TimeRange(1320,1800))));
        assertThat(result.slotsByDate().get(date.plusDays(1))).containsExactly(new TimeRange(360,480));
    }
    @Test void unavailableOnlyNeverCreatesAnAvailableWindow() {
        assertThat(UserAvailability.subtract("u",true,null,Map.of(date,List.of(new TimeRange(480,600))))
            .slotsByDate()).isEmpty();
    }
}
