package thesis.project.gu.service;

import org.junit.jupiter.api.Test;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.application.LightweightRequestPreParser;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LightweightRequestPreParserTest {
    private final LightweightRequestPreParser parser = new LightweightRequestPreParser();

    @Test
    void parsesStructuredRequestIntoDeterministicHints() {
        CreatePlanReq req = new CreatePlanReq(
                "Brisbane",
                3,
                1200,
                new CreatePlanReq.Party(2, 1),
                List.of("culture", "market_shopping", "theme-park"),
                "relaxed",
                "local-fast",
                null
        );

        ParsedPlanningRequest parsed = parser.parse(req);

        assertThat(parsed.destinationCandidate()).isEqualTo("Brisbane");
        assertThat(parsed.daysCandidate()).isEqualTo(3);
        assertThat(parsed.travellers()).isEqualTo(3);
        assertThat(parsed.pace()).isEqualTo("relaxed");
        assertThat(parsed.budget()).isEqualTo(1200);
        assertThat(parsed.preferenceHints()).containsExactly("culture", "shopping", "theme_park");
        assertThat(parsed.lateStartPreferred()).isTrue();
        assertThat(parsed.familyFriendly()).isTrue();
        assertThat(parsed.preferIndoorWhenRaining()).isTrue();
        assertThat(parsed.specialDayHints()).isEmpty();
    }

    @Test
    void normalizesFastPaceAndIndoorHints() {
        CreatePlanReq req = new CreatePlanReq(
                "Brisbane",
                1,
                null,
                new CreatePlanReq.Party(1, 0),
                List.of("rainy-day", "food"),
                "fast pace",
                "local-fast",
                null
        );

        ParsedPlanningRequest parsed = parser.parse(req);

        assertThat(parsed.pace()).isEqualTo("rush");
        assertThat(parsed.preferenceHints()).containsExactly("indoor", "local-dining");
        assertThat(parsed.preferIndoorWhenRaining()).isTrue();
        assertThat(parsed.lateStartPreferred()).isFalse();
    }

    @Test
    void nullRequestUsesSafeDefaults() {
        ParsedPlanningRequest parsed = parser.parse(null);

        assertThat(parsed.daysCandidate()).isEqualTo(1);
        assertThat(parsed.travellers()).isEqualTo(2);
        assertThat(parsed.pace()).isEqualTo("normal");
        assertThat(parsed.preferenceHints()).isEmpty();
        assertThat(parsed.familyFriendly()).isFalse();
    }
}
