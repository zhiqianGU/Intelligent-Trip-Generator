package thesis.project.gu.util;

import org.junit.jupiter.api.Test;
import thesis.project.gu.req.CreatePlanReq;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripPromptTemplateTest {

    @Test
    void dayUserIncludesUsedPoiHintsToBlockCrossDayDuplicates() {
        String prompt = TripPromptTemplate.dayUser(
                req(),
                2,
                4,
                "day-2{primaryArea=CBD,effectiveNonMeal=2-3}",
                "day-1{primaryArea=South Bank,effectiveNonMeal=2-3}",
                "City Botanic Gardens; Queensland Museum"
        );

        assertThat(prompt)
                .contains("Already used non-meal POIs on other generated or locked days:")
                .contains("City Botanic Gardens; Queensland Museum")
                .contains("must not repeat any non-meal POI listed in the already used non-meal POIs block");
    }

    @Test
    void dayUserFallsBackToNoneWhenUsedPoiHintsMissing() {
        String prompt = TripPromptTemplate.dayUser(
                req(),
                1,
                3,
                "day-1{primaryArea=CBD,effectiveNonMeal=2-3}",
                "",
                null
        );

        assertThat(prompt)
                .contains("Already used non-meal POIs on other generated or locked days:\nnone");
    }

    private CreatePlanReq req() {
        return new CreatePlanReq(
                "Brisbane",
                4,
                null,
                new CreatePlanReq.Party(2, 0),
                List.of("culture"),
                "relaxed",
                "qwen-max",
                null
        );
    }
}
