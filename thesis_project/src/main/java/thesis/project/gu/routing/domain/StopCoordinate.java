package thesis.project.gu.routing.domain;

public record StopCoordinate(double lat, double lon) {
    public String asLatLon() {
        return lat + "," + lon;
    }
}
