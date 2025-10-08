package thesis.project.gu.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// 2. 路线规划响应
public record CarRouteResponse(
        @JsonProperty("status")     String status,
        @JsonProperty("info")       String info,
        @JsonProperty("infocode")   String infocode,
        @JsonProperty("count")      String count,
        @JsonProperty("route")      Route  route
) {
    public record Route(
            @JsonProperty("origin")      String origin,
            @JsonProperty("destination") String destination,
            @JsonProperty("paths")       List<Path> paths
    ) {}

    public record Path(
            @JsonProperty("distance")       String distance,
            @JsonProperty("duration")       String duration,
            @JsonProperty("strategy")       String strategy,
            @JsonProperty("tolls")          String tolls,
            @JsonProperty("toll_distance")  String tollDistance,
            @JsonProperty("toll_road")      List<String> tollRoad,
            @JsonProperty("restriction")    String restriction,
            @JsonProperty("traffic_lights") String trafficLights,
            @JsonProperty("steps")          List<Step> steps,
            @JsonProperty("polyline") String polyline    // ← 新增这一行
    ) {}

    public record Step(
            @JsonProperty("instruction")      String instruction,
            @JsonProperty("orientation")      String orientation,
            @JsonProperty("road")             String road,
            @JsonProperty("distance")         String distance,
            @JsonProperty("tolls")            String tolls,
            @JsonProperty("toll_distance")    String tollDistance,
            @JsonProperty("toll_road")        List<String> tollRoad,
            @JsonProperty("duration")         String duration,
            @JsonProperty("polyline")         String polyline,
            @JsonProperty("action")
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<String> action,
            @JsonProperty("assistant_action")
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<String> assistantAction
    ) {}
}