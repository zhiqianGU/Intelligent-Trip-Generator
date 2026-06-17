package thesis.project.gu.catalog.domain;

public record Destination(
        String destinationId,
        String city,
        String state,
        String country,
        String timezone,
        boolean resolved
) {
    public static Destination unresolved(String city) {
        return new Destination(null, city, null, null, null, false);
    }
}
