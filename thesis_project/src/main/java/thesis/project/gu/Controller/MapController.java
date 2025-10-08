package thesis.project.gu.Controller;


import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import thesis.project.gu.dto.PlaceSuggestionDto;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.model.Place;
import thesis.project.gu.response.GeoResponse;
import thesis.project.gu.service.MapService;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/map")
@Validated
public class MapController {

    private final MapService mapService;
    private final ExecutorService routeExecutor =
            new ThreadPoolExecutor(
                    8,  // corePoolSize
                    16, // maximumPoolSize
                    30, TimeUnit.SECONDS, // keepAliveTime
                    new LinkedBlockingQueue<>(200), // 队列容量
                    Executors.defaultThreadFactory(),
                    new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
            );
    public MapController(MapService mapService) {
        this.mapService = mapService;
    }

    @GetMapping(value = "/geocode", produces = MediaType.APPLICATION_JSON_VALUE)
    public GeoResponse geocode(
            @RequestParam @NotBlank(message = "address cannot be blank") String address,
            @RequestParam(required = false) String city
    ) {
        return mapService.geocode(address, city);
    }

    @PostMapping(value = "/geocode/persist", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Place> geocodeAndPersist(
            @RequestParam @NotBlank String address,
            @RequestParam(required = false) String city) {
        return mapService.geocodeAndPersist(address, city);
    }


//
//    @GetMapping("/suggestions")
//    public List<PlaceSuggestionDto> suggestions(
//            @RequestParam String address,
//            @RequestParam(required = false) String city
//    ) {
//        // 走新的 geocode(address, city)
//        GeoResponse resp = mapService.geocode(address, city);
//        if (resp == null || resp.geocodes() == null || resp.geocodes().isEmpty()) {
//            return List.of();
//        }
//
//        // 映射 + 容错 + 去重（按 location 去重）+ 截断前 10 条
//        return resp.geocodes().stream()
//                .filter(Objects::nonNull)
//                .map(g -> {
//                    // location: "lng,lat" → 解析失败则丢弃该条
//                    String loc = g.location();
//                    if (loc == null || loc.isBlank()) return null;
//                    String[] parts = loc.split(",");
//                    if (parts.length != 2) return null;
//
//                    BigDecimal lng;
//                    BigDecimal lat;
//                    try {
//                        lng = new BigDecimal(parts[0].trim());
//                        lat = new BigDecimal(parts[1].trim());
//                    } catch (NumberFormatException e) {
//                        return null;
//                    }
//
//                    // 名称展示：优先 formatted_address；否则拼 street + number；最后兜底 address
//                    String formatted = g.formattedAddress(); // 高德字段
//                    List<String> streetList = g.street();
//                    List<String> numberList = g.number();
//
//                    String street = (streetList != null && !streetList.isEmpty())
//                            ? String.join("", streetList) : "";
//                    String number = (numberList != null && !numberList.isEmpty())
//                            ? String.join("", numberList) : "";
//
//                    String baseName = (formatted != null && !formatted.isBlank())
//                            ? formatted
//                            : (!street.isBlank() || !number.isBlank()
//                            ? (street + number)
//                            : address);
//
//                    // 追加级别信息（可读性更好）
//                    String level = g.level();
//                    String levelTag = switch (level == null ? "" : level) {
//                        case "门址" -> "（门址）";
//                        case "兴趣点" -> " ";
//                        default -> "";
//                    };
//
//                    return new PlaceSuggestionDto(
//                            loc,                // poiId：沿用你现有逻辑，用坐标字符串
//                            baseName + levelTag,
//                            lat,
//                            lng,
//                            address             // 原始查询词
//                    );
//                })
//                .filter(Objects::nonNull)
//                // 去重：同一坐标只保留一条
//                .collect(Collectors.collectingAndThen(
//                        Collectors.toMap(
//                                PlaceSuggestionDto::poiId,      // key
//                                Function.identity(),            // value
//                                (a, b) -> a,                    // 冲突保留第一条
//                                LinkedHashMap::new              // 保持顺序
//                        ),
//                        m -> m.values().stream().limit(10).toList()
//                ));
//    }
//
//
//
//
//
    @GetMapping("/route")
    public Map<String, Object> unifiedRoute(
        @RequestParam String type,
        @RequestParam String origin,       // "lat,lon" 例如 -27.488256685224457,153.02680037397556
        @RequestParam String destination   // "lat,lon"
    ) {
    CompletableFuture<Object> mainFuture = CompletableFuture.supplyAsync(
            () -> switch (type) {
                case "drive" -> mapService.car_route(origin, destination);
                case "walk"  -> mapService.walk_route(origin, destination);
                case "transit" -> mapService.transit_route(origin, destination);
                default -> throw ErrorCode.PARAM_ERROR.ex("unsupported type: " + type);
            }, routeExecutor);

    CompletableFuture<MapService.RouteSummary> walkSummary =
            CompletableFuture.supplyAsync(() -> mapService.walk_summary(origin, destination), routeExecutor);

    CompletableFuture<MapService.RouteSummary> carSummary =
            CompletableFuture.supplyAsync(() -> mapService.car_summary(origin, destination), routeExecutor);

    CompletableFuture<MapService.RouteSummary> transitSummary =
            CompletableFuture.supplyAsync(() -> mapService.transit_summary(origin, destination), routeExecutor);

    CompletableFuture.allOf(mainFuture, walkSummary, carSummary, transitSummary).join();

    Map<String, Object> result = new HashMap<>();
    result.put("main", mainFuture.join());
    result.put("walk_summary", walkSummary.join());
    result.put("car_summary", carSummary.join());
    result.put("transit_summary", transitSummary.join());
    return result;
}
}

