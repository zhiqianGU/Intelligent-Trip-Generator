package thesis.project.gu.routing.domain;

import thesis.project.gu.weather.infrastructure.WeatherApiClient;
import java.time.LocalDate;

public record RouteRecommendationContext(
        boolean hasKids,
        LocalDate departureDate,
        WeatherApiClient.Forecast forecast
) {}
