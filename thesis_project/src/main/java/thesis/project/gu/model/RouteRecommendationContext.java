package thesis.project.gu.model;

import thesis.project.gu.client.WeatherApiClient;
import java.time.LocalDate;

public record RouteRecommendationContext(
        boolean hasKids,
        LocalDate departureDate,
        WeatherApiClient.Forecast forecast
) {}
