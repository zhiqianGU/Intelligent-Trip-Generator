package thesis.project.gu.observability.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.observability.domain.ApiMetricDaily;
import thesis.project.gu.observability.application.DailyApiMetricsService;
import thesis.project.gu.observability.application.RuntimeMetricsService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {
    private final RuntimeMetricsService runtimeMetricsService;
    private final DailyApiMetricsService dailyApiMetricsService;

    public AnalysisController(RuntimeMetricsService runtimeMetricsService, DailyApiMetricsService dailyApiMetricsService) {
        this.runtimeMetricsService = runtimeMetricsService;
        this.dailyApiMetricsService = dailyApiMetricsService;
    }

    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> metrics() {
        return runtimeMetricsService.snapshot();
    }

    @GetMapping(value = "/daily", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ApiMetricDaily> daily(@RequestParam(defaultValue = "14") int days) {
        return dailyApiMetricsService.recentDays(days);
    }
}
