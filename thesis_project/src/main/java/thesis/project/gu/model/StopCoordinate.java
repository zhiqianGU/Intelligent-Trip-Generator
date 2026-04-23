package thesis.project.gu.model;

public record StopCoordinate(double lat, double lon) {
    public String asLatLon() {
        return lat + "," + lon;
    }
}
