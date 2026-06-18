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
            List<TripSlot> slots,
            List<String> fallbackZoneIds
    ) {
        public DaySkeleton(
                int day,
                String theme,
                String zoneId,
                String startTime,
                List<TripSlot> slots
        ) {
            this(day, theme, zoneId, startTime, slots, List.of());
        }

        public DaySkeleton {
            slots = slots == null ? List.of() : List.copyOf(slots);
            fallbackZoneIds = fallbackZoneIds == null ? List.of() : List.copyOf(fallbackZoneIds);
        }
    }
}
