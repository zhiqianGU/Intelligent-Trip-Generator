package thesis.project.gu.planning.domain;

import java.util.List;

public record TripSkeleton(
        List<DaySkeleton> days
) {
    public record DaySkeleton(
            int day,
            String theme,
            String zoneId,
            String startTime,
            List<TripSlot> slots
    ) {
    }
}
