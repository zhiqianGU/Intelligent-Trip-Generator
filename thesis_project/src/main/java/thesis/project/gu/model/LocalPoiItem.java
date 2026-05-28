package thesis.project.gu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LocalPoiItem(
        String name,
        String type,
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
    public boolean hasMealType(String mealType) {
        return mealTypes != null && mealTypes.stream()
                .anyMatch(value -> value != null && value.equalsIgnoreCase(mealType));
    }

    public boolean hasStyleTag(String styleTag) {
        return styleTags != null && styleTags.stream()
                .anyMatch(value -> value != null && value.equalsIgnoreCase(styleTag));
    }
}
