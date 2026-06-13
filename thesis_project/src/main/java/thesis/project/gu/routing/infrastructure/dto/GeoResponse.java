package thesis.project.gu.routing.infrastructure.dto;

import com.fasterxml.jackson.annotation.*;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoResponse(
        String type,
        List<Feature> features
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feature(
            String type,
            Geometry geometry,
            Properties properties
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Geometry(
            String type,
            List<Double> coordinates // [lon, lat]
    ) {}

    /** Geoapify 的主要地址字段都在 properties 里 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(
            @JsonProperty("formatted") String formatted,   // e.g. "548 Mission St, San Francisco..."
            @JsonProperty("country")   String country,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("state")     String state,
            @JsonProperty("city")      String city,
            @JsonProperty("postcode")  String postcode,
            @JsonProperty("street")    String street,
            @JsonProperty("housenumber") String housenumber,
            @JsonProperty("lat")       Double lat,
            @JsonProperty("lon")       Double lon,
            @JsonProperty("result_type") String resultType,
            @JsonProperty("rank")      Object rank,        // 保留原样（打分/等级信息，按需可建子结构）
            @JsonProperty("place_id")  String placeId
    ) {}
}
