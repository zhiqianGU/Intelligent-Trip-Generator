package thesis.project.gu.model;

public class PlaceAnchorOverride {
    private Long id;
    private String matchName;
    private String matchCity;
    private String matchCountry;
    private Double latitude;
    private Double longitude;
    private String displayName;
    private String note;
    private Boolean enabled;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMatchName() {
        return matchName;
    }

    public void setMatchName(String matchName) {
        this.matchName = matchName;
    }

    public String getMatchCity() {
        return matchCity;
    }

    public void setMatchCity(String matchCity) {
        this.matchCity = matchCity;
    }

    public String getMatchCountry() {
        return matchCountry;
    }

    public void setMatchCountry(String matchCountry) {
        this.matchCountry = matchCountry;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
