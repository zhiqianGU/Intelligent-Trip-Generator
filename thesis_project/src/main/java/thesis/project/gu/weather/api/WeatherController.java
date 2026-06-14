package thesis.project.gu.weather.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.weather.infrastructure.WeatherApiClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
public class WeatherController {
    private final WeatherApiClient weatherApiClient;

    public WeatherController(WeatherApiClient weatherApiClient) {
        this.weatherApiClient = weatherApiClient;
    }

    @PostMapping(value = "/weather", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public WeatherForecastResponse weatherForecast(@RequestBody WeatherForecastRequest request) {
        if (request == null || request.city() == null || request.city().isBlank()
                || request.departureDate() == null || request.departureDate().isBlank()
                || request.days() == null || request.days() < 1) {
            return new WeatherForecastResponse(false, List.of());
        }
        LocalDate departureDate = parseDate(request.departureDate());
        if (departureDate == null) {
            return new WeatherForecastResponse(false, List.of());
        }
        WeatherApiClient.Forecast forecast = weatherApiClient.forecast(request.city(), request.departureDate(), request.days());

        List<WeatherDaySummary> days = new ArrayList<>();
        for (int i = 0; i < request.days(); i++) {
            LocalDate date = departureDate.plusDays(i);
            WeatherApiClient.WeatherDay weatherDay = forecast == null || forecast.isEmpty() ? null : forecast.days().get(date.toString());
            if (weatherDay == null) {
                days.add(defaultSunnyWeatherDay(i + 1, date));
                continue;
            }
            days.add(new WeatherDaySummary(
                    i + 1,
                    date.toString(),
                    weatherDay.condition(),
                    weatherDay.dailyChanceOfRain(),
                    weatherDay.avgTempC(),
                    weatherDay.maxTempC(),
                    weatherDay.minTempC(),
                    forecast.rainyAt(date, null)
            ));
        }
        return new WeatherForecastResponse(true, days);
    }

    private WeatherDaySummary defaultSunnyWeatherDay(int dayIndex, LocalDate date) {
        return new WeatherDaySummary(
                dayIndex,
                date == null ? "" : date.toString(),
                "Sunny",
                0,
                null,
                null,
                null,
                false
        );
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record WeatherForecastRequest(String city, String departureDate, Integer days) {}

    public record WeatherForecastResponse(boolean available, List<WeatherDaySummary> days) {}

    public record WeatherDaySummary(
            int dayIndex,
            String date,
            String condition,
            int chanceOfRain,
            Double avgTempC,
            Double maxTempC,
            Double minTempC,
            boolean rainy
    ) {}
}
