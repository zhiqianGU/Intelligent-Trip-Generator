package thesis.project.gu.planning.domain;

import thesis.project.gu.catalog.local.LocalPoiItem;

import java.util.List;
import java.util.Locale;

public record PlaceCandidate(
        String name,
        PlaceCandidateType type,
        String category,
        String city,
        String area,
        String addressLine,
        Double latitude,
        Double longitude,
        Integer stayMinutes,
        List<String> styleTags,
        List<String> timeSlots,
        Integer priority,
        Boolean familyFriendly,
        String budgetLevel,
        List<String> mealTypes,
        String cuisine,
        String source,
        String verificationLevel
) {
    public static PlaceCandidate fromLocalPoiItem(LocalPoiItem item, PlaceCandidateType fallbackType) {
        if (item == null) {
            return null;
        }
        return new PlaceCandidate(
                item.name(),
                resolveType(item.type(), fallbackType),
                item.category(),
                item.city(),
                item.area(),
                item.addressLine(),
                item.latitude(),
                item.longitude(),
                item.stayMinutes(),
                safeCopy(item.styleTags()),
                safeCopy(item.timeSlots()),
                item.priority(),
                item.familyFriendly(),
                item.budgetLevel(),
                safeCopy(item.mealTypes()),
                item.cuisine(),
                item.source(),
                item.verificationLevel()
        );
    }

    public LocalPoiItem toLocalPoiItem() {
        return new LocalPoiItem(
                name,
                type == null ? null : type.name().toLowerCase(Locale.ROOT),
                category,
                city,
                area,
                addressLine,
                latitude,
                longitude,
                stayMinutes,
                styleTags,
                timeSlots,
                priority,
                familyFriendly,
                budgetLevel,
                mealTypes,
                cuisine,
                source,
                verificationLevel
        );
    }

    public boolean hasMealType(String mealType) {
        return mealTypes != null && mealTypes.stream()
                .anyMatch(value -> value != null && value.equalsIgnoreCase(mealType));
    }

    public boolean hasStyleTag(String styleTag) {
        return styleTags != null && styleTags.stream()
                .anyMatch(value -> value != null && value.equalsIgnoreCase(styleTag));
    }

    private static PlaceCandidateType resolveType(String type, PlaceCandidateType fallbackType) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "hotel", "lodging", "accommodation" -> PlaceCandidateType.HOTEL;
            case "restaurant", "cafe", "food", "dining" -> PlaceCandidateType.RESTAURANT;
            case "attraction", "poi", "place" -> PlaceCandidateType.ATTRACTION;
            default -> fallbackType;
        };
    }

    private static List<String> safeCopy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
