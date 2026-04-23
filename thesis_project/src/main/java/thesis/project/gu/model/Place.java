package thesis.project.gu.model;

import java.math.BigDecimal;

public class Place {


    private Long id;
    private String name;
    private String address;
    private String city;
    private String district;
    private String country;
    private Double latitude;
    private Double longitude;
    private String source;       // GEOAPIFY
    private String externalRef;
    private String websiteUri;
    private String googleMapsUri;
    private String businessStatus;

    public Place(Long id, String name, String city, String address, String district, Double latitude, String country, Double longitude, String source, String externalRef) {
        this.id = id;
        this.name = name;
        this.city = city;
        this.address = address;
        this.district = district;
        this.latitude = latitude;
        this.country = country;
        this.longitude = longitude;
        this.source = source;
        this.externalRef = externalRef;
    }



    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
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

    public String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getWebsiteUri() {
        return websiteUri;
    }

    public void setWebsiteUri(String websiteUri) {
        this.websiteUri = websiteUri;
    }

    public String getGoogleMapsUri() {
        return googleMapsUri;
    }

    public void setGoogleMapsUri(String googleMapsUri) {
        this.googleMapsUri = googleMapsUri;
    }

    public String getBusinessStatus() {
        return businessStatus;
    }

    public void setBusinessStatus(String businessStatus) {
        this.businessStatus = businessStatus;
    }



    public Place() {

    }




}
