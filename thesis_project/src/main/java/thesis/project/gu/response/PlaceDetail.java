package thesis.project.gu.response;

public record PlaceDetail(
        Long id,
        String name,
        String addressLine,
        String city,
        String district,
        String country,
        Double latitude,
        Double longitude,
        String external_ref,
        String websiteUri,
        String googleMapsUri,
        String businessStatus
) {}
