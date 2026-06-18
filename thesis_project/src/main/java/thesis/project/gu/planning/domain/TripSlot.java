package thesis.project.gu.planning.domain;

import java.util.List;

public record TripSlot(
        String slotId,
        SlotType slotType,
        String zoneId,
        List<String> requiredCapabilities,
        Integer preferredDurationMinutes,
        TimeWindow preferredTimeWindow
) {
    public enum SlotType {
        ACTIVITY,
        LUNCH,
        DINNER,
        SPECIAL_EVENT
    }

    public record TimeWindow(String start, String end) {
    }
}
