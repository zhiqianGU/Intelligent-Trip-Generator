package thesis.project.gu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.local.LocalPoiCatalogService;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.localfast.LocalPlanGeneratorService;
import thesis.project.gu.planning.quality.LocalPlanQualityDiagnosticService;
import thesis.project.gu.planning.quality.LocalPlanQualityReport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPlanQualityDiagnosticServiceTest {
    private final LocalPoiCatalogService catalogService = new LocalPoiCatalogService(new ObjectMapper());
    private final LocalPlanGeneratorService generatorService = new LocalPlanGeneratorService(catalogService);
    private final LocalPlanQualityDiagnosticService diagnosticService = new LocalPlanQualityDiagnosticService();

    @Test
    void generatedTwentyDayBrisbanePlanHasNoHardQualityErrors() {
        PlanDraftResponse draft = generatorService.generate(req(20));

        LocalPlanQualityReport report = diagnosticService.diagnoseResponse(draft);

        assertThat(report.errorCount()).isZero();
        assertThat(report.score()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void domainDiagnosticPathAcceptsPlanDraft() {
        PlanDraft draft = PlanDraft.fromResponse(generatorService.generate(req(3)));

        LocalPlanQualityReport report = diagnosticService.diagnose(draft);

        assertThat(report.errorCount()).isZero();
        assertThat(report.score()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void diagnosticReportsObviousQualityRegressions() {
        PlanDraftResponse draft = new PlanDraftResponse(
                "Brisbane",
                "Australia",
                1,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "normal",
                "Broken plan",
                "Broken plan",
                List.of(new PlanDraftResponse.DayPlan(
                        1,
                        null,
                        List.of(
                                place("Museum", "museum", null, "Brisbane CBD", "09:00", "11:00", null, -27.4689, 153.0235),
                                place("Late Lunch", "restaurant", "lunch", "Brisbane CBD", "14:30", "15:30", "lunch", -27.471, 153.0272),
                                place("Museum", "museum", null, "Brisbane CBD", "15:00", "16:00", null, -27.4689, 153.0235),
                                place("Far Dinner", "restaurant", "dinner", "Northshore Hamilton", "16:30", "17:30", "dinner", -27.4418, 153.0781)
                        ),
                        "CBD day",
                        null,
                        null,
                        null,
                        "CBD day"
                )),
                "local-fast"
        );

        LocalPlanQualityReport report = diagnosticService.diagnoseResponse(draft);

        assertThat(report.hasErrors()).isTrue();
        assertThat(report.warnings()).extracting(LocalPlanQualityReport.Warning::code)
                .contains("lunch-window", "dinner-window", "time-overlap", "duplicate-non-meal");
    }

    private CreatePlanReq req(int days) {
        return new CreatePlanReq(
                "Brisbane",
                days,
                1200,
                new CreatePlanReq.Party(2, 0),
                List.of(),
                "normal",
                "local-fast",
                null
        );
    }

    private PlanDraftResponse.Place place(
            String name,
            String category,
            String timeSlot,
            String area,
            String start,
            String end,
            String mealType,
            double latitude,
            double longitude
    ) {
        return new PlanDraftResponse.Place(
                name,
                name + " address",
                area,
                "Brisbane",
                "QLD",
                null,
                "Australia",
                category,
                60,
                timeSlot,
                start,
                end,
                mealType,
                area,
                null,
                null,
                "medium",
                "reason",
                "tip",
                null,
                null,
                "OPERATIONAL",
                null,
                latitude,
                longitude
        );
    }
}
