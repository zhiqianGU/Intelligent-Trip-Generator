package thesis.project.gu.routing.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoRouteResponse(
        String type,
        List<Feature> features
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feature(
            String type,
            Properties properties,
            Geometry geometry
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Geometry(
            String type,                 // "MultiLineString"
            List<List<List<Double>>> coordinates // [[[lon,lat],...], ...]
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(
            String mode,                 // drive/walk/bicycle/transit
            String units,                // metric
            Double distance,            // meters
            @JsonProperty("distance_units") String distanceUnits,
            Double time,                // seconds
            List<Leg> legs
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leg(
            Double distance,
            Double time,
            List<Step> steps
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(
            @JsonProperty("from_index") Integer fromIndex,
            @JsonProperty("to_index") Integer toIndex,
            Double distance,
            Double time,
            Instruction instruction
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Instruction(String text) {}
}
