package thesis.project.gu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import thesis.project.gu.client.GooglePlacesClient;
import thesis.project.gu.model.StopCoordinate;
import thesis.project.gu.req.CreatePlanReq;
import thesis.project.gu.response.PlanDraftResponse;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlanProcessorServiceTest {
    @Mock
    private TripAiService aiService;
    @Mock
    private CacheSerive cacheSerive;
    @Mock
    private HotelVerificationService hotelVerificationService;
    @Mock
    private RestaurantVerificationService restaurantVerificationService;
    @Mock
    private MapService mapService;
    @Mock
    private GooglePlacesClient googlePlacesClient;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private LocalPlanGeneratorService localPlanGeneratorService;
    @Mock
    private LocalPlanQualityDiagnosticService localPlanQualityDiagnosticService;

    private ObjectMapper objectMapper;
    private PlanProcessorService service;
    private PlanQualityMetricsService planQualityMetricsService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        planQualityMetricsService = new PlanQualityMetricsService();
        DaySkeletonService daySkeletonService = new DaySkeletonService();
        PlaceHeuristicService placeHeuristicService = new PlaceHeuristicService();
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new PlanProcessorService(
                aiService,
                cacheSerive,
                objectMapper,
                hotelVerificationService,
                restaurantVerificationService,
                mapService,
                googlePlacesClient,
                planQualityMetricsService,
                daySkeletonService,
                placeHeuristicService,
                stringRedisTemplate,
                localPlanGeneratorService,
                localPlanQualityDiagnosticService
        );
        service.clearRouteChoiceCrossRequestCache();
    }

    @Test
    void generateDraftUsesLocalFastGeneratorWithoutAiOrCopyPolish() throws Exception {
        CreatePlanReq localReq = new CreatePlanReq("Brisbane", 10, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "local-fast", null);
        PlanDraftResponse localDraft = validDraft(List.of(
                place("Local Park", "park", "morning", "09:00", "10:00", null, "Local", "Local tip", -27.470, 153.020),
                place("Local Lunch", "restaurant", "lunch", "12:15", "13:15", "lunch", "Lunch", "Lunch tip", -27.471, 153.021),
                place("Local Dinner", "restaurant", "dinner", "17:30", "18:30", "dinner", "Dinner", "Dinner tip", -27.472, 153.022)
        ));
        when(localPlanGeneratorService.generate(localReq)).thenReturn(localDraft);
        when(localPlanQualityDiagnosticService.diagnose(any())).thenReturn(new LocalPlanQualityReport(100, 0, 0, List.of()));

        PlanDraftResponse result = service.generateDraft(localReq, false, true);

        assertThat(result.copyPolishStatus()).isEqualTo("deferred");
        assertThat(result.city()).isEqualTo(localDraft.city());
        assertThat(result.daysPlan()).hasSize(localDraft.daysPlan().size());
        verify(localPlanGeneratorService).generate(localReq);
        verify(localPlanQualityDiagnosticService).diagnose(result);
        verify(aiService, never()).generatePlanRaw(any());
        verify(aiService, never()).generatePlanRawPhased(any());
        verify(aiService, never()).polishPlanCopy(any());
    }

    @Test
    void processDraftRunsCopyPolishAndKeepsStructuralFieldsFromVerifiedDraft() throws Exception {
        PlanDraftResponse verified = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old reason", "Old tip", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Lunch reason", "Lunch tip", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "18:00", null, "View reason", "View tip", -27.472, 153.022),
                place("City Garden", "park", "sunset", "18:20", "18:50", null, "Garden reason", "Garden tip", -27.473, 153.023),
                place("Dinner Spot", "restaurant", "dinner", "19:10", "20:10", "dinner", "Dinner reason", "Dinner tip", -27.474, 153.024)
        ));
        PlanDraftResponse polished = new PlanDraftResponse(
                "Wrong City",
                "Wrong Country",
                99,
                "XXX",
                new CreatePlanReq.Party(9, 9),
                "rush",
                "Wrong title",
                "Polished overview with timed entry.",
                List.of(new PlanDraftResponse.DayPlan(
                        99,
                        place("Wrong Hotel", "hotel", "morning", "08:00", "09:00", null, "Hotel polished", "Hotel polished tip", 1.0, 2.0),
                        List.of(place("Wrong Stop", "shop", "night", "22:00", "23:00", null, "Polished reason by bus.", "Polished tip.", 3.0, 4.0)),
                        "Polished theme",
                        "Polished morning",
                        "Polished afternoon",
                        "Polished evening",
                        "Polished note"
                )),
                null
        );

        passThroughVerification();
        when(aiService.polishPlanCopy(any())).thenReturn(new TripAiService.CopyPolishResult(polished, "completed"));

        PlanDraftResponse result = service.processExistingRawDraft(req(List.of()), objectMapper.writeValueAsString(verified), false, 0);

        assertThat(result.copyPolishStatus()).isEqualTo("completed");
        assertThat(result.city()).isEqualTo("Brisbane");
        assertThat(result.days()).isEqualTo(1);
        assertThat(result.daysPlan()).hasSize(1);
        assertThat(result.daysPlan().getFirst().stops()).hasSize(5);
        assertThat(result.daysPlan().getFirst().stops().getFirst().name()).isEqualTo("Museum");
        assertThat(result.daysPlan().getFirst().stops().getFirst().category()).isEqualTo("museum");
        assertThat(result.daysPlan().getFirst().stops().getFirst().startTime()).isEqualTo("10:00");
        assertThat(result.daysPlan().getFirst().eveningNote()).isEqualTo("Evening");
        assertThat(result.overview()).isEqualTo("Polished overview with entry requirements.");
        verify(aiService).polishPlanCopy(any());
    }

    @Test
    void finalCopySanitizerRemovesUnsupportedDayNarrativeClaims() throws Exception {
        PlanDraftResponse verified = validDraft(List.of(
                place("Dreamworld Theme Park (Morning)", "theme_park", "morning", "09:00", "12:00", null, "Old reason", "Old tip", -27.864, 153.316),
                place("Dreamworld Theme Park Internal Dining Break", "dining", "lunch", "12:20", "13:20", "lunch", "Lunch reason", "Lunch tip", -27.864, 153.316),
                place("Dreamworld Theme Park (Afternoon)", "theme_park", "afternoon", "13:20", "16:30", null, "Old reason", "Old tip", -27.864, 153.316),
                place("Dinner Spot", "restaurant", "dinner", "17:30", "19:00", "dinner", "Dinner reason", "Dinner tip", -27.867, 153.318)
        ));
        PlanDraftResponse.DayPlan day = verified.daysPlan().getFirst();
        PlanDraftResponse polished = new PlanDraftResponse(
                verified.city(),
                verified.country(),
                verified.days(),
                verified.currency(),
                verified.party(),
                verified.pace(),
                verified.title(),
                verified.overview(),
                List.of(new PlanDraftResponse.DayPlan(
                        day.dayIndex(),
                        day.hotel(),
                        List.of(
                                place("Dreamworld Theme Park (Morning)", "theme_park", "morning", "09:00", "12:00", null,
                                        "Taronga Zoo is the day's primary attraction, offering wildlife and animal encounters before shifting to Luna Park.",
                                        "Ferry to Taronga Zoo early for quieter animal viewing.", -27.864, 153.316),
                                place("Dreamworld Theme Park Internal Dining Break", "dining", "lunch", "12:20", "13:20", "lunch",
                                        "Lunch reason", "Lunch tip", -27.864, 153.316),
                                place("Dreamworld Theme Park (Afternoon)", "theme_park", "afternoon", "13:20", "16:30", null,
                                        "Continue with scenic views after lunch.", "Rides operate weather-permitting check the park map for shorter queues.", -27.864, 153.316),
                                place("Dinner Spot", "restaurant", "dinner", "17:30", "19:00", "dinner",
                                        "Dinner reason with skyline views.", "Reserve dinner ahead to guarantee seating at upper-level tables.", -27.867, 153.318)
                        ),
                        "Luna Park - the only verified, accessible, and operationally active theme park in Sydney.",
                        "Ferry to Taronga Zoo for animal encounters and wildlife, then explore Luna Park.",
                        "Enjoy lunch at Riva St Kilda, then explore Lavender Bay's leafy streets and small galleries.",
                        "Have dinner at Bistro Rex after visiting Luna Park's entrance plaza for scenic views.",
                        "A compact, compact day anchored at Luna Park and enhanced by harbour lookouts and quiet bayside moments. End with dinner at Quay Restaurant."
                )),
                null
        );

        passThroughVerification();
        when(aiService.polishPlanCopy(any())).thenReturn(new TripAiService.CopyPolishResult(polished, "completed"));

        PlanDraftResponse result = service.processExistingRawDraft(req(List.of("theme_park")), objectMapper.writeValueAsString(verified), false, 0);

        PlanDraftResponse.DayPlan resultDay = result.daysPlan().getFirst();
        String dayCopy = String.join(" ",
                resultDay.theme(),
                resultDay.morningNote(),
                resultDay.afternoonNote(),
                resultDay.eveningNote(),
                resultDay.note()
        ).toLowerCase();
        assertThat(dayCopy)
                .doesNotContain("cadman")
                .doesNotContain("circular quay lookout")
                .doesNotContain("ferry from")
                .doesNotContain("big dipper")
                .doesNotContain("only verified")
                .doesNotContain("operationally active")
                .doesNotContain("elevated terraces")
                .doesNotContain("small galleries")
                .doesNotContain("leafy streets")
                .doesNotContain("entrance plaza")
                .doesNotContain("harbour lookouts")
                .doesNotContain("quiet bayside moments")
                .doesNotContain("compact, compact")
                .doesNotContain("riva st kilda")
                .doesNotContain("bistro rex")
                .doesNotContain("quay restaurant")
                .doesNotContain("taronga")
                .doesNotContain("animal encounters")
                .doesNotContain("wildlife")
                .doesNotContain("scenic views");
        assertThat(resultDay.theme()).isNotBlank();
        assertThat(resultDay.morningNote()).isNotBlank();
        assertThat(resultDay.afternoonNote()).isNotBlank();
        assertThat(resultDay.note()).isNotBlank();
        String stopCopy = resultDay.stops().stream()
                .map(stop -> String.join(" ", stop.reason(), stop.tip()))
                .collect(java.util.stream.Collectors.joining(" "))
                .toLowerCase();
        assertThat(stopCopy)
                .doesNotContain("taronga")
                .doesNotContain("animal encounters")
                .doesNotContain("wildlife")
                .doesNotContain("ferry to")
                .doesNotContain("reserve dinner ahead")
                .doesNotContain("guarantee seating")
                .doesNotContain("upper-level tables")
                .doesNotContain("scenic views")
                .doesNotContain("skyline views");
        assertThat(resultDay.stops()).allSatisfy(stop -> {
            assertThat(stop.reason()).isNotBlank();
            assertThat(stop.tip()).isNotBlank();
        });
    }

    @Test
    void finalCopySanitizerBackfillsBlankCopyFields() throws Exception {
        PlanDraftResponse verified = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "", "", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "", "", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "", "", -27.472, 153.022),
                place("City Garden", "park", "afternoon", "15:20", "16:20", null, "", "", -27.473, 153.023),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "", "", -27.474, 153.024)
        ));
        PlanDraftResponse.DayPlan day = verified.daysPlan().getFirst();
        PlanDraftResponse polished = new PlanDraftResponse(
                verified.city(),
                verified.country(),
                verified.days(),
                verified.currency(),
                verified.party(),
                verified.pace(),
                "",
                "",
                List.of(new PlanDraftResponse.DayPlan(
                        day.dayIndex(),
                        place("Hotel", "hotel", "morning", "08:00", "09:00", null, "", "", -27.469, 153.019),
                        day.stops(),
                        "",
                        "",
                        "",
                        "",
                        ""
                )),
                null
        );

        passThroughVerification();
        when(aiService.polishPlanCopy(any())).thenReturn(new TripAiService.CopyPolishResult(polished, "completed"));

        PlanDraftResponse result = service.processExistingRawDraft(req(List.of()), objectMapper.writeValueAsString(verified), false, 0);

        assertThat(result.title()).isNotBlank();
        assertThat(result.overview()).isNotBlank();
        PlanDraftResponse.DayPlan resultDay = result.daysPlan().getFirst();
        assertThat(resultDay.theme()).isNotBlank();
        assertThat(resultDay.morningNote()).isNotBlank();
        assertThat(resultDay.afternoonNote()).isNotBlank();
        assertThat(resultDay.eveningNote()).isNotBlank();
        assertThat(resultDay.note()).isNotBlank();
        assertThat(resultDay.hotel().reason()).isNotBlank();
        assertThat(resultDay.hotel().tip()).isNotBlank();
        assertThat(resultDay.stops()).allSatisfy(stop -> {
            assertThat(stop.reason()).isNotBlank();
            assertThat(stop.tip()).isNotBlank();
        });
    }

    @Test
    void finalCopySanitizerFixesMarketBeforeLunchNarrativeWhenLunchComesFirst() throws Exception {
        PlanDraftResponse verified = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:30", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Lunch", "Lunch tip", -27.471, 153.021),
                place("City Market", "market_shopping", "afternoon", "13:30", "14:30", null, "Market", "Market tip", -27.472, 153.022),
                place("River View", "attraction", "afternoon", "15:00", "16:00", null, "View", "View tip", -27.473, 153.023),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Dinner", "Dinner tip", -27.473, 153.023)
        ));
        PlanDraftResponse.DayPlan day = verified.daysPlan().getFirst();
        verified = new PlanDraftResponse(
                verified.city(),
                verified.country(),
                verified.days(),
                verified.currency(),
                verified.party(),
                verified.pace(),
                verified.title(),
                verified.overview(),
                List.of(new PlanDraftResponse.DayPlan(
                        day.dayIndex(),
                        day.hotel(),
                        day.stops(),
                        "Market and Culture",
                        "Start with the museum.",
                        "Explore the local market and enjoy a leisurely lunch.",
                        "End with dinner.",
                        "A culture day with market shopping and lunch."
                )),
                null
        );
        day = verified.daysPlan().getFirst();
        PlanDraftResponse polished = new PlanDraftResponse(
                verified.city(),
                verified.country(),
                verified.days(),
                verified.currency(),
                verified.party(),
                verified.pace(),
                verified.title(),
                verified.overview(),
                List.of(new PlanDraftResponse.DayPlan(
                        day.dayIndex(),
                        day.hotel(),
                        day.stops(),
                        "Market and Culture",
                        "Start with the museum.",
                        "Explore the local market and enjoy a leisurely lunch.",
                        "End with dinner.",
                        "A culture day with market shopping and lunch."
                )),
                null
        );

        passThroughVerification();
        when(aiService.polishPlanCopy(any())).thenReturn(new TripAiService.CopyPolishResult(polished, "completed"));

        PlanDraftResponse result = service.processExistingRawDraft(req(List.of("market_shopping")), objectMapper.writeValueAsString(verified), false, 0);

        assertThat(result.daysPlan().getFirst().afternoonNote())
                .isEqualTo("After lunch, continue with the market stop and afternoon visit.");
    }

    @Test
    void retryBranchRunsPostRoutePruneAndNarrativeRewriteBeforeReturning() throws Exception {
        PlanDraftResponse invalidInitial = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021)
        ));
        PlanDraftResponse retry = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Shopping Mall", "shop", "afternoon", "11:30", "12:00", null, "Old", "Old", -27.471, 153.021),
                place("Lunch Spot", "restaurant", "lunch", "12:15", "13:00", "lunch", "Old", "Old", -27.472, 153.022),
                place("River View", "attraction", "afternoon", "14:00", "16:00", null, "Old", "Old", -27.473, 153.023),
                place("City Garden", "park", "sunset", "16:20", "17:20", null, "Old", "Old", -27.474, 153.024),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.475, 153.025)
        ));

        passThroughVerification();
        when(aiService.regeneratePlanRaw(any(), anyString(), any())).thenReturn(objectMapper.writeValueAsString(retry));
        when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

        PlanDraftResponse result = service.processExistingRawDraft(req(List.of()), objectMapper.writeValueAsString(invalidInitial), false, 0);

        List<String> names = result.daysPlan().getFirst().stops().stream().map(PlanDraftResponse.Place::name).toList();
        assertThat(names).doesNotContain("Shopping Mall");
        PlanDraftResponse.Place museum = result.daysPlan().getFirst().stops().getFirst();
        assertThat(museum.reason()).contains("main cultural block");
        assertThat(result.copyPolishStatus()).isEqualTo("fallback-disabled");
        verify(aiService).regeneratePlanRaw(
                any(),
                argThat(text -> text != null && text.contains("day-1{primaryArea=") && text.contains("effectiveNonMeal=")),
                argThat(text -> text != null && text.contains("day-1{primaryArea=") && text.contains("effectiveNonMeal="))
        );
    }

    @Test
    void generateDraftUsesPhasedAiGenerationWhenFlagEnabled() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "true");
        try {
            PlanDraftResponse phased = validDraft(List.of(
                    place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                    place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                    place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                    place("City Garden", "park", "sunset", "16:00", "17:00", null, "Old", "Old", -27.473, 153.023),
                    place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024)
            ));
            passThroughVerification();
            when(aiService.generatePlanRawPhased(any())).thenReturn(objectMapper.writeValueAsString(phased));
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            PlanDraftResponse result = service.generateDraft(req(List.of()), false);

            assertThat(result.daysPlan()).hasSize(1);
            verify(aiService).generatePlanRawPhased(any());
            verify(aiService, never()).generatePlanRaw(any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void generateDraftUsesPhasedAiGenerationForLongTripsEvenWhenFlagDisabled() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "false");
        try {
            PlanDraftResponse phased = validDraft(List.of(
                    place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                    place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                    place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                    place("City Garden", "park", "sunset", "16:00", "17:00", null, "Old", "Old", -27.473, 153.023),
                    place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024)
            ));
            
            passThroughVerification();
            String rawJson = objectMapper.writeValueAsString(phased);
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            // 尝试模拟 10 天的请求和 10 天的响应
            CreatePlanReq realLongReq = new CreatePlanReq("Brisbane", 10, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null);
            
            // 为了通过校验，响应必须包含 10 个 DayPlan 元素，且不能有跨天重复 POI
            java.util.List<thesis.project.gu.response.PlanDraftResponse.DayPlan> tenDays = new java.util.ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                final int dayIdx = i;
                java.util.List<thesis.project.gu.response.PlanDraftResponse.Place> uniqueStops = phased.daysPlan().get(0).stops().stream()
                        .map(p -> new thesis.project.gu.response.PlanDraftResponse.Place(
                                p.name() + " Day " + dayIdx, p.addressLine(), p.suburb(), p.city(), p.state(), p.postcode(), p.country(),
                                p.category(), p.stayMinutes(), p.timeSlot(), p.startTime(), p.endTime(), p.mealType(),
                                p.preferredArea(), p.cuisine(), p.vibe(), p.budgetLevel(), p.reason(), p.tip(),
                                p.websiteUri(), p.googleMapsUri(), p.businessStatus(), p.url(), p.latitude(), p.longitude()
                        )).toList();
                tenDays.add(new thesis.project.gu.response.PlanDraftResponse.DayPlan(i, phased.daysPlan().get(0).hotel(), uniqueStops, "Theme", "M", "A", "E", "N"));
            }
            
            PlanDraftResponse longPhased = new PlanDraftResponse(phased.city(), phased.country(), 10, phased.currency(), phased.party(), phased.pace(), phased.title(), phased.overview(), tenDays, phased.copyPolishStatus());
            String longRawJson = objectMapper.writeValueAsString(longPhased);
            
            when(aiService.generatePlanRawPhased(any())).thenReturn(longRawJson);
            lenient().when(aiService.regeneratePlanRaw(any(), anyString(), any())).thenReturn(longRawJson);

            service.generateDraft(realLongReq, false);

            verify(aiService).generatePlanRawPhased(any());
            verify(aiService, never()).generatePlanRaw(any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void retryUsesPhasedDayLevelRegenerationForMediumTripsWhenFlagDisabled() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "false");
        try {
            java.util.List<PlanDraftResponse.DayPlan> initialDays = new java.util.ArrayList<>();
            java.util.List<PlanDraftResponse.DayPlan> patchedDays = new java.util.ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                java.util.List<PlanDraftResponse.Place> initialStops = new java.util.ArrayList<>(List.of(
                        place("Museum Day " + i, "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                        place("Lunch Spot Day " + i, "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                        place("River View Day " + i, "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                        place("City Garden Day " + i, "park", "sunset", "16:00", "17:00", null, "Old", "Old", -27.473, 153.023)
                ));
                if (i != 2) {
                    initialStops.add(place("Dinner Spot Day " + i, "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024));
                }
                java.util.List<PlanDraftResponse.Place> patchedStops = new java.util.ArrayList<>(initialStops);
                if (i == 2) {
                    patchedStops.add(place("Dinner Spot Day 2", "restaurant", "dinner", "18:00", "19:00", "dinner", "Patched", "Patched", -27.474, 153.024));
                }
                initialDays.add(new PlanDraftResponse.DayPlan(
                        i,
                        place("Hotel Day " + i, "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                        initialStops,
                        "Theme " + i,
                        "Morning",
                        "Afternoon",
                        "Evening",
                        "Note"
                ));
                patchedDays.add(new PlanDraftResponse.DayPlan(
                        i,
                        place("Hotel Day " + i, "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                        patchedStops,
                        "Theme " + i,
                        "Morning",
                        "Afternoon",
                        "Evening",
                        "Note"
                ));
            }

            PlanDraftResponse invalidInitial = new PlanDraftResponse(
                    "Brisbane",
                    "Australia",
                    3,
                    "AUD",
                    new CreatePlanReq.Party(2, 0),
                    "normal",
                    "Brisbane 3 day",
                    "Overview",
                    initialDays,
                    null
            );
            PlanDraftResponse patchedRetry = new PlanDraftResponse(
                    "Brisbane",
                    "Australia",
                    3,
                    "AUD",
                    new CreatePlanReq.Party(2, 0),
                    "normal",
                    "Brisbane 3 day",
                    "Overview",
                    patchedDays,
                    null
            );

            passThroughVerification();
            when(aiService.generatePlanRaw(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            when(aiService.regeneratePlanDaysPhased(any(), any(), any(), any())).thenReturn(patchedRetry);
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            CreatePlanReq mediumReq = new CreatePlanReq("Brisbane", 3, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null);
            PlanDraftResponse result = service.generateDraft(mediumReq, false);

            assertThat(result.daysPlan()).hasSize(3);
            assertThat(result.daysPlan().get(1).stops())
                    .extracting(PlanDraftResponse.Place::name)
                    .contains("Dinner Spot Day 2");
            verify(aiService).generatePlanRaw(any());
            verify(aiService, never()).generatePlanRawPhased(any());
            verify(aiService).regeneratePlanDaysPhased(
                    any(),
                    any(),
                    argThat(days -> days != null && days.equals(List.of(2))),
                    any()
            );
            verify(aiService, never()).regeneratePlanRaw(any(), anyString(), any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void wholePlanRetryInstructionAddsHardConstraintsAndCompactsIssueSummary() throws Exception {
        java.util.List<String> issues = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            issues.add("day-1-stop-" + i + "-gap-too-large");
        }

        String instruction = retryInstruction(
                req(List.of()),
                issues,
                validDraft(List.of(
                        place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                        place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                        place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                        place("City Garden", "park", "sunset", "16:00", "17:00", null, "Old", "Old", -27.473, 153.023),
                        place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024)
                ))
        );

        assertThat(instruction)
                .contains("Hard constraints: include exactly one real lunch and one real dinner per day")
                .contains("Fix these highest-priority validation issues first")
                .contains("...(+8 more)");
    }

    @Test
    void generateDraftRecoversFromMalformedInitialJsonByRegeneratingWholePlan() throws Exception {
        String malformedRaw = """
                {
                  "city":"Brisbane",
                  "country":"Australia",
                  "days":1,
                  "currency":"AUD",
                  "party":{"adults":2,"kids":0},
                  "pace":"normal",
                  "title":"Broken",
                  "overview":"Broken",
                  "daysPlan":[
                    {
                      "dayIndex":1,
                      "hotel":{"name":"Hotel","addressLine":"Hotel address","suburb":"CBD","city":"Brisbane","state":"QLD","postcode":"4000","country":"Australia","category":"hotel","stayMinutes":60,"timeSlot":"morning","startTime":"08:00","endTime":"09:00","mealType":null,"preferredArea":null,"cuisine":null,"vibe":null,"budgetLevel":null,"reason":"Hotel reason","tip":"Hotel tip","websiteUri":null,"googleMapsUri":null,"businessStatus":"OPERATIONAL","url":null,"latitude":-27.469,"longitude":153.019},
                      "stops":[
                        {"name":"Museum","addressLine":"Museum address","suburb":"CBD","city":"Brisbane","state":"QLD","postcode":"4000","country":"Australia","category":"museum","stayMinutes":60,"timeSlot":"morning","startTime":"10:00","endTime":"11:00","mealType":null,"preferredArea":null,"cuisine":null,"vibe":null,"budgetLevel":null,"reason":"Old","tip":"Old","websiteUri":null,"googleMapsUri":null,"businessStatus":"OPERATIONAL","url":null,"latitude":-27.47,"longitude":153.02},
                        {"name":"Lunch Spot","addressLine":"Lunch Spot address
                """;
        PlanDraftResponse repaired = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("City Garden", "park", "sunset", "16:00", "17:00", null, "Old", "Old", -27.473, 153.023),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024)
        ));

        passThroughVerification();
        when(aiService.generatePlanRaw(any())).thenReturn(malformedRaw);
        when(aiService.regeneratePlanRaw(any(), anyString(), any())).thenReturn(objectMapper.writeValueAsString(repaired));
        when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

        PlanDraftResponse result = service.generateDraft(req(List.of()), false);

        assertThat(result.daysPlan()).hasSize(1);
        assertThat(result.daysPlan().getFirst().stops())
                .extracting(PlanDraftResponse.Place::name)
                .contains("Dinner Spot");
        verify(aiService).regeneratePlanRaw(any(), anyString(), any());
    }

    @Test
    void generateDraftRetriesInvalidJsonRepairMoreThanOnceBeforeFailing() throws Exception {
        String malformedRaw = """
                {
                  "city":"Brisbane",
                  "country":"Australia",
                  "days":1,
                  "currency":"AUD",
                  "party":{"adults":2,"kids":0},
                  "pace":"normal",
                  "title":"Broken",
                  "overview":"Broken",
                  "daysPlan":[
                    {
                      "dayIndex":1,
                      "hotel":{"name":"Hotel","addressLine":"Hotel address","suburb":"CBD","city":"Brisbane","state":"QLD","postcode":"4000","country":"Australia","category":"hotel","stayMinutes":60,"timeSlot":"morning","startTime":"08:00","endTime":"09:00","mealType":null,"preferredArea":null,"cuisine":null,"vibe":null,"budgetLevel":null,"reason":"Hotel reason","tip":"Hotel tip","websiteUri":null,"googleMapsUri":null,"businessStatus":"OPERATIONAL","url":null,"latitude":-27.469,"longitude":153.019},
                      "stops":[{"name":"Museum","addressLine":"Museum address
                """;
        PlanDraftResponse repaired = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("City Garden", "park", "sunset", "16:00", "17:00", null, "Old", "Old", -27.473, 153.023),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024)
        ));

        passThroughVerification();
        when(aiService.generatePlanRaw(any())).thenReturn(malformedRaw);
        when(aiService.regeneratePlanRaw(any(), anyString(), any()))
                .thenReturn(malformedRaw)
                .thenReturn(objectMapper.writeValueAsString(repaired));
        when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

        PlanDraftResponse result = service.generateDraft(req(List.of()), false);

        assertThat(result.daysPlan().getFirst().stops())
                .extracting(PlanDraftResponse.Place::name)
                .contains("Dinner Spot");
        verify(aiService, org.mockito.Mockito.times(2)).regeneratePlanRaw(any(), anyString(), any());
    }

    @Test
    void phasedRetryRegeneratesOnlyFailedDaysInsteadOfWholePlan() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "true");
        try {
            PlanDraftResponse invalidInitial = validDraft(List.of(
                    place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                    place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021)
            ));
            PlanDraftResponse patchedRetry = validDraft(List.of(
                    place("Museum", "museum", "morning", "10:00", "11:00", null, "Patched", "Patched", -27.470, 153.020),
                    place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Patched", "Patched", -27.471, 153.021),
                    place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Patched", "Patched", -27.472, 153.022),
                    place("City Garden", "park", "sunset", "16:00", "17:00", null, "Patched", "Patched", -27.473, 153.023),
                    place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Patched", "Patched", -27.474, 153.024)
            ));

            passThroughVerification();
            when(aiService.generatePlanRawPhased(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            when(aiService.regeneratePlanDaysPhased(any(), any(), any(), any())).thenReturn(patchedRetry);
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            PlanDraftResponse result = service.generateDraft(req(List.of()), false);

            assertThat(result.daysPlan()).hasSize(1);
            assertThat(result.daysPlan().getFirst().stops())
                    .extracting(PlanDraftResponse.Place::name)
                    .contains("Dinner Spot");
            verify(aiService).regeneratePlanDaysPhased(
                    any(),
                    any(),
                    argThat(days -> days != null && days.equals(List.of(1))),
                    argThat(map -> map instanceof Map<?, ?>
                            && map.containsKey(1)
                            && map.get(1) instanceof String text
                            && text.contains("Preserve unchanged stops when possible")
                            && text.contains("mustKeep=Museum[morning,10:00-11:00] | Lunch Spot[lunch,12:15-13:15]")
                            && text.contains("mayRetime=none")
                            && text.contains("mayReplace=none")
                            && text.contains("mayInsertAround=none")
                            && text.contains("Current day context:")
                            && text.contains("stops=Museum[morning,10:00-11:00] -> Lunch Spot[lunch,12:15-13:15]")
                            && text.contains("meals[lunch=present,dinner=missing]")
                            && text.contains("nonMeal=count 1, target 1-2")
                            && text.contains("Add exactly one real dinner venue")
                            && text.contains("Follow this day skeleton strictly"))
            );
            verify(aiService, never()).regeneratePlanRaw(any(), anyString(), any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void phasedRetryBuildsTypedDayInstructionForGapIssue() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "true");
        try {
            PlanDraftResponse invalidInitial = validDraft(List.of(
                    place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                    place("Lunch Spot", "restaurant", "lunch", "14:30", "15:30", "lunch", "Old", "Old", -27.471, 153.021),
                    place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
            ), "fast-pace");
            PlanDraftResponse patchedRetry = validDraft(List.of(
                    place("Museum", "museum", "morning", "10:00", "11:00", null, "Patched", "Patched", -27.470, 153.020),
                    place("River Walk", "attraction", "afternoon", "12:00", "13:00", null, "Patched", "Patched", -27.472, 153.022),
                    place("Lunch Spot", "restaurant", "lunch", "13:10", "14:00", "lunch", "Patched", "Patched", -27.471, 153.021),
                    place("City Garden", "park", "sunset", "14:20", "15:20", null, "Patched", "Patched", -27.473, 153.023),
                    place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Patched", "Patched", -27.474, 153.024)
            ), "fast-pace");

            passThroughVerification();
            when(aiService.generatePlanRawPhased(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            when(aiService.regeneratePlanDaysPhased(any(), any(), any(), any())).thenReturn(patchedRetry);
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            service.generateDraft(req(List.of(), "fast-pace"), false);

            verify(aiService).regeneratePlanDaysPhased(
                    any(),
                    any(),
                    argThat(days -> days != null && days.equals(List.of(1))),
                    argThat(map -> map instanceof Map<?, ?>
                            && map.containsKey(1)
                            && map.get(1) instanceof String text
                            && text.contains("Preserve unchanged stops when possible")
                            && text.contains("mustKeep=Museum[morning,10:00-11:00] | Lunch Spot[lunch,12:15-13:15]")
                            && text.contains("mayRetime=Dinner Spot[dinner,17:30-18:30]")
                            && text.contains("mayReplace=none")
                            && text.contains("mayInsertAround=none")
                            && text.contains("Current day context:")
                            && text.contains("stops=Museum[morning,10:00-11:00] -> Lunch Spot[lunch,12:15-13:15] -> Dinner Spot[dinner,17:30-18:30]")
                            && text.contains("meals[lunch=present,dinner=present]")
                            && text.contains("nonMeal=count 1, target 1-2")
                            && text.contains("Tighten the schedule so adjacent stops do not have oversized idle gaps")
                            && !text.contains("Fix these validation issues for day 1"))
            );
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void phasedRetryFallsBackToWholePlanForNonPatchableIssues() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "true");
        try {
            PlanDraftResponse invalidInitial = validDraft(List.of(
                    place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                    place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                    place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                    place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
            ));
            PlanDraftResponse wholePlanRetry = validDraft(List.of(
                    place("Museum", "museum", "morning", "10:00", "11:00", null, "Retry", "Retry", -27.470, 153.020),
                    place("Market Hall", "market_shopping", "afternoon", "14:00", "15:00", null, "Retry", "Retry", -27.472, 153.022),
                    place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Retry", "Retry", -27.471, 153.021),
                    place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Retry", "Retry", -27.473, 153.023)
            ));

            passThroughVerification();
            when(aiService.generatePlanRawPhased(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            when(aiService.regeneratePlanRaw(any(), anyString(), any())).thenReturn(objectMapper.writeValueAsString(wholePlanRetry));
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            PlanDraftResponse result = service.generateDraft(req(List.of("market_shopping")), false);

            assertThat(result.daysPlan().getFirst().stops())
                    .extracting(PlanDraftResponse.Place::name)
                    .contains("Market Hall");
            verify(aiService, never()).regeneratePlanDaysPhased(any(), any(), any(), any());
            verify(aiService).regeneratePlanRaw(any(), anyString(), any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void generateDraftShortCircuitsAiRetryWhenInitialIssuesAreDuplicateDominated() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "false");
        try {
            List<PlanDraftResponse.DayPlan> initialDays = List.of(
                    dayPlan(1, List.of(
                            place("City Botanic Gardens", "park", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                            place("Lunch 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                            place("Gallery 1", "gallery", "afternoon", "13:20", "14:20", null, "Old", "Old", -27.472, 153.022),
                            place("Dinner 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
                    )),
                    dayPlan(2, List.of(
                            place("City Botanic Gardens", "park", "morning", "09:30", "10:30", null, "Old", "Old", -27.470, 153.020),
                            place("Lunch 2", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.474, 153.024),
                            place("Gallery 2", "gallery", "afternoon", "13:20", "14:20", null, "Old", "Old", -27.475, 153.025),
                            place("Dinner 2", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.476, 153.026)
                    )),
                    dayPlan(3, List.of(
                            place("City Botanic Gardens", "park", "morning", "09:40", "10:40", null, "Old", "Old", -27.470, 153.020),
                            place("Lunch 3", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.477, 153.027),
                            place("Gallery 3", "gallery", "afternoon", "13:20", "14:20", null, "Old", "Old", -27.478, 153.028),
                            place("Dinner 3", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.479, 153.029)
                    ))
            );
            PlanDraftResponse invalidInitial = multiDayDraft(3, initialDays, "normal");

            passThroughVerification();
            when(aiService.generatePlanRaw(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            PlanDraftResponse result = service.generateDraft(
                    new CreatePlanReq("Brisbane", 3, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null),
                    false
            );

            assertThat(service.validateDraft(result, new CreatePlanReq("Brisbane", 3, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null)))
                    .doesNotContain("duplicate-poi-across-days");
            verify(aiService).generatePlanRaw(any());
            verify(aiService, never()).regeneratePlanDaysPhased(any(), any(), any(), any());
            verify(aiService, never()).regeneratePlanRaw(any(), anyString(), any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void generateDraftUsesLocalRescueBeforeAiRetryForMealAndDuplicateIssues() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "false");
        try {
            PlanDraftResponse invalidInitial = new PlanDraftResponse(
                    "Brisbane",
                    "Australia",
                    2,
                    "AUD",
                    new CreatePlanReq.Party(2, 0),
                    "normal",
                    "Brisbane 2 day",
                    "Overview",
                    List.of(
                            dayPlan(1, List.of(
                                    place("City Botanic Gardens", "park", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                    place("Lunch 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                                    place("Gallery 1", "gallery", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                                    place("Dinner 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
                            )),
                            dayPlan(2, List.of(
                                    place("City Botanic Gardens", "park", "morning", "09:30", "10:30", null, "Old", "Old", -27.470, 153.020),
                                    place("Lunch 2", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.474, 153.024),
                                    place("Gallery 2", "gallery", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.475, 153.025)
                            ))
                    ),
                    null
            );
            PlanDraftResponse rescued = new PlanDraftResponse(
                    "Brisbane",
                    "Australia",
                    2,
                    "AUD",
                    new CreatePlanReq.Party(2, 0),
                    "normal",
                    "Brisbane 2 day",
                    "Overview",
                    List.of(
                            dayPlan(1, List.of(
                                    place("City Botanic Gardens", "park", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                    place("Lunch 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                                    place("Gallery 1", "gallery", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                                    place("Dinner 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
                            )),
                            dayPlan(2, List.of(
                                    place("South Bank Parklands", "park", "morning", "09:30", "10:30", null, "Patched", "Patched", -27.478, 153.020),
                                    place("Lunch 2", "restaurant", "lunch", "12:00", "13:00", "lunch", "Patched", "Patched", -27.474, 153.024),
                                    place("Gallery 2", "gallery", "afternoon", "14:00", "15:00", null, "Patched", "Patched", -27.475, 153.025),
                                    place("Dinner 2", "restaurant", "dinner", "18:00", "19:00", "dinner", "Patched", "Patched", -27.476, 153.026)
                            ))
                    ),
                    null
            );

            passThroughVerification();
            PlanProcessorService spyService = spy(service);
            when(aiService.generatePlanRaw(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            doReturn(rescued).when(spyService).repairMealStops(any(), any());
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            PlanDraftResponse result = spyService.generateDraft(
                    new CreatePlanReq("Brisbane", 2, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null),
                    false
            );

            assertThat(result.daysPlan().get(1).stops())
                    .extracting(PlanDraftResponse.Place::name)
                    .contains("Dinner 2")
                    .doesNotContain("City Botanic Gardens");
            verify(aiService).generatePlanRaw(any());
            verify(aiService, never()).regeneratePlanDaysPhased(any(), any(), any(), any());
            verify(aiService, never()).regeneratePlanRaw(any(), anyString(), any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void processDraftSkipsFollowupMealVerificationWhenRepairChangesNoMealStops() throws Exception {
        PlanDraftResponse draft = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Lunch", "Lunch", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Dinner", "Dinner", -27.473, 153.023)
        ));

        PlanProcessorService spyService = spy(service);
        when(hotelVerificationService.verifyAndNormalize(any())).thenAnswer(invocation ->
                new HotelVerificationService.VerificationResult(invocation.getArgument(0), List.of()));
        when(restaurantVerificationService.verifyAndNormalize(any())).thenReturn(
                new RestaurantVerificationService.VerificationResult(draft, List.of("day-1-stop-2-google-places-no-match"))
        );
        doReturn(draft).when(spyService).repairMealStops(any(), any());

        Method method = PlanProcessorService.class.getDeclaredMethod(
                "verifyAndRepairEntities",
                PlanDraftResponse.class,
                String.class,
                StringBuilder.class,
                StringBuilder.class
        );
        method.setAccessible(true);
        Object result = method.invoke(spyService, draft, "initial", new StringBuilder(), new StringBuilder());

        assertThat(result).isNotNull();
        verify(restaurantVerificationService).verifyAndNormalize(any());
        verify(restaurantVerificationService, never()).verifyAndNormalizeSelective(any(), any());
    }

    @Test
    void localRescueAcceptsGooglePlacesNoMatchCombinedWithMissingMeals() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "false");
        try {
            PlanDraftResponse invalidInitial = new PlanDraftResponse(
                    "Brisbane",
                    "Australia",
                    2,
                    "AUD",
                    new CreatePlanReq.Party(2, 0),
                    "normal",
                    "Brisbane 2 day",
                    "Overview",
                    List.of(
                            dayPlan(1, List.of(
                                    place("Museum 1", "museum", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                    place("Lunch 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                                    place("Gallery 1", "gallery", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                                    place("Dinner 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
                            )),
                            dayPlan(2, List.of(
                                    place("Museum 2", "museum", "morning", "09:00", "10:00", null, "Old", "Old", -27.474, 153.024),
                                    place("Bad Dinner", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.475, 153.025)
                            ))
                    ),
                    null
            );

            PlanDraftResponse rescued = new PlanDraftResponse(
                    "Brisbane",
                    "Australia",
                    2,
                    "AUD",
                    new CreatePlanReq.Party(2, 0),
                    "normal",
                    "Brisbane 2 day",
                    "Overview",
                    List.of(
                            dayPlan(1, List.of(
                                    place("Museum 1", "museum", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                    place("Lunch 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                                    place("Gallery 1", "gallery", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                                    place("Dinner 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
                            )),
                            dayPlan(2, List.of(
                                    place("Museum 2", "museum", "morning", "09:00", "10:00", null, "Old", "Old", -27.474, 153.024),
                                    place("Rescued Lunch", "restaurant", "lunch", "12:00", "13:00", "lunch", "Patched", "Patched", -27.475, 153.025),
                                    place("Rescued Dinner", "restaurant", "dinner", "18:00", "19:00", "dinner", "Patched", "Patched", -27.476, 153.026)
                            ))
                    ),
                    null
            );

            passThroughVerification();
            PlanProcessorService spyService = spy(service);
            when(aiService.generatePlanRaw(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            doReturn(
                    List.of(
                            "day-2-stop-2-google-places-no-match",
                            "day-2-missing-lunch",
                            "day-2-missing-dinner"
                    ),
                    List.of()
            ).when(spyService).validateDraft(any(), any());
            doReturn(rescued).when(spyService).repairMealStops(any(), any());
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            PlanDraftResponse result = spyService.generateDraft(
                    new CreatePlanReq("Brisbane", 2, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null),
                    false
            );

            assertThat(result.daysPlan().get(1).stops())
                    .extracting(PlanDraftResponse.Place::name)
                    .contains("Rescued Lunch", "Rescued Dinner")
                    .doesNotContain("Bad Dinner");
            verify(aiService).generatePlanRaw(any());
            verify(aiService, never()).regeneratePlanRaw(any(), anyString(), any());
            verify(aiService, never()).regeneratePlanDaysPhased(any(), any(), any(), any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void longTripWholePlanRetryUsesPhasedDayRegenerationBeforeMonolithicRetry() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "true");
        try {
            List<PlanDraftResponse.DayPlan> initialDays = new java.util.ArrayList<>();
            List<PlanDraftResponse.DayPlan> patchedDays = new java.util.ArrayList<>();
            for (int dayIndex = 1; dayIndex <= 7; dayIndex++) {
                initialDays.add(dayPlan(dayIndex, List.of(
                        place("Museum " + dayIndex, "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470 + (dayIndex * 0.001), 153.020 + (dayIndex * 0.001)),
                        place("Lunch " + dayIndex, "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471 + (dayIndex * 0.001), 153.021 + (dayIndex * 0.001)),
                        place("Gallery " + dayIndex, "gallery", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472 + (dayIndex * 0.001), 153.022 + (dayIndex * 0.001)),
                        place("Dinner " + dayIndex, "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473 + (dayIndex * 0.001), 153.023 + (dayIndex * 0.001))
                )));
                patchedDays.add(dayPlan(dayIndex, List.of(
                        place(dayIndex == 1 ? "Botanic Garden" : "Museum " + dayIndex, dayIndex == 1 ? "park" : "museum", "morning", "10:00", "11:00", null, "Patched", "Patched", -27.470 + (dayIndex * 0.001), 153.020 + (dayIndex * 0.001)),
                        place("Lunch " + dayIndex, "restaurant", "lunch", "12:00", "13:00", "lunch", "Patched", "Patched", -27.471 + (dayIndex * 0.001), 153.021 + (dayIndex * 0.001)),
                        place("Gallery " + dayIndex, "gallery", "afternoon", "14:00", "15:00", null, "Patched", "Patched", -27.472 + (dayIndex * 0.001), 153.022 + (dayIndex * 0.001)),
                        place("Dinner " + dayIndex, "restaurant", "dinner", "18:00", "19:00", "dinner", "Patched", "Patched", -27.473 + (dayIndex * 0.001), 153.023 + (dayIndex * 0.001))
                )));
            }

            PlanDraftResponse invalidInitial = multiDayDraft(7, initialDays, "normal");
            PlanDraftResponse patchedRetry = multiDayDraft(7, patchedDays, "normal");

            passThroughVerification();
            when(aiService.generatePlanRawPhased(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            when(aiService.regeneratePlanDaysPhased(any(), any(), any(), any())).thenReturn(patchedRetry);
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            CreatePlanReq longReq = new CreatePlanReq("Brisbane", 7, null, new CreatePlanReq.Party(2, 0), List.of("nature"), "normal", "qwen-max", null);
            PlanDraftResponse result = service.generateDraft(longReq, false);

            assertThat(result.daysPlan()).hasSize(7);
            assertThat(result.daysPlan().getFirst().stops())
                    .extracting(PlanDraftResponse.Place::name)
                    .contains("Botanic Garden");
            verify(aiService).regeneratePlanDaysPhased(
                    any(),
                    any(),
                    argThat(days -> days != null && days.equals(List.of(1, 2, 3, 4, 5, 6, 7))),
                    argThat(map -> map instanceof Map<?, ?>
                            && map.size() == 7
                            && map.get(1) instanceof String text
                            && text.contains("Help resolve these whole-trip issues while keeping this day stable: style-missing-nature.")
                            && text.contains("Keep strings minimal"))
            );
            verify(aiService, never()).regeneratePlanRaw(any(), anyString(), any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void longTripWholePlanRetryTargetsDuplicateIssueDaysAndAnchorDaysOnly() throws Exception {
        String previous = System.getProperty("itrip.plan.phased.enabled");
        System.setProperty("itrip.plan.phased.enabled", "true");
        try {
            List<PlanDraftResponse.DayPlan> initialDays = new java.util.ArrayList<>();
            for (int dayIndex = 1; dayIndex <= 7; dayIndex++) {
                List<PlanDraftResponse.Place> stops = new java.util.ArrayList<>(List.of(
                        place("Museum " + dayIndex, "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470 + (dayIndex * 0.001), 153.020 + (dayIndex * 0.001)),
                        place("Lunch " + dayIndex, "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471 + (dayIndex * 0.001), 153.021 + (dayIndex * 0.001)),
                        place("Gallery " + dayIndex, "gallery", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472 + (dayIndex * 0.001), 153.022 + (dayIndex * 0.001)),
                        place("Dinner " + dayIndex, "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473 + (dayIndex * 0.001), 153.023 + (dayIndex * 0.001))
                ));
                if (dayIndex == 2) {
                    stops.set(0, place("Shared Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.468, 153.021));
                }
                if (dayIndex == 5) {
                    stops.set(0, place("Shared Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.465, 153.024));
                }
                initialDays.add(dayPlan(dayIndex, stops));
            }

            PlanDraftResponse invalidInitial = multiDayDraft(7, initialDays, "normal");
            PlanDraftResponse patchedRetry = multiDayDraft(7, initialDays, "normal");

            passThroughVerification();
            when(aiService.generatePlanRawPhased(any())).thenReturn(objectMapper.writeValueAsString(invalidInitial));
            when(aiService.regeneratePlanDaysPhased(any(), any(), any(), any())).thenReturn(patchedRetry);
            when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

            PlanDraftResponse result = service.generateDraft(new CreatePlanReq("Brisbane", 7, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null), false);

            assertThat(result.daysPlan()).hasSize(7);
            verify(aiService).regeneratePlanDaysPhased(
                    any(),
                    any(),
                    argThat(days -> days != null && days.equals(List.of(2, 5))),
                    any()
            );
            verify(aiService, never()).regeneratePlanRaw(any(), anyString(), any());
        } finally {
            if (previous == null) {
                System.clearProperty("itrip.plan.phased.enabled");
            } else {
                System.setProperty("itrip.plan.phased.enabled", previous);
            }
        }
    }

    @Test
    void retryInstructionForDaySplitsNonMealExpansionIntoReplaceAndInsertAroundBuckets() throws Exception {
        PlanDraftResponse draft = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:15", "13:15", "lunch", "Old", "Old", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
        ), "fast-pace");

        String instruction = retryInstructionForDay(
                req(List.of(), "fast-pace"),
                1,
                List.of("day-1-too-few-non-meal-stops"),
                draft
        );

        assertThat(instruction)
                .contains("Preserve unchanged stops when possible")
                .contains("mustKeep=Lunch Spot[lunch,12:15-13:15] | Dinner Spot[dinner,18:00-19:00]")
                .contains("mayRetime=none")
                .contains("mayReplace=Museum[morning,10:00-11:00] | River View[afternoon,14:00-15:00]")
                .contains("mayInsertAround=Museum[morning,10:00-11:00] | River View[afternoon,14:00-15:00]")
                .contains("Increase the number of non-meal stops for this day until it satisfies the skeleton effective range");
    }

    @Test
    void retryInstructionForDayKeepsExistingStopsStableWhenAddingMissingDinner() throws Exception {
        PlanDraftResponse draft = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:15", "13:15", "lunch", "Old", "Old", -27.471, 153.021)
        ));

        String instruction = retryInstructionForDay(
                req(List.of()),
                1,
                List.of("day-1-missing-dinner"),
                draft
        );

        assertThat(instruction)
                .contains("Preserve unchanged stops when possible")
                .contains("mustKeep=Museum[morning,10:00-11:00] | Lunch Spot[lunch,12:15-13:15]")
                .contains("mayRetime=none")
                .contains("mayReplace=none")
                .contains("mayInsertAround=none")
                .contains("Add exactly one real dinner venue in the evening window");
    }

    @Test
    void retryInstructionForDayAllowsReplacingCrossDayDuplicatePoi() throws Exception {
        PlanDraftResponse draft = new PlanDraftResponse(
                "Brisbane",
                "Australia",
                2,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "normal",
                "Brisbane 2 day",
                "Overview",
                List.of(
                        new PlanDraftResponse.DayPlan(
                                1,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                                        place("Dinner Spot 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.472, 153.022)
                                ),
                                "Theme 1",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        ),
                        new PlanDraftResponse.DayPlan(
                                2,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot", "restaurant", "lunch", "12:15", "13:15", "lunch", "Old", "Old", -27.471, 153.021),
                                        place("Dinner Spot", "restaurant", "dinner", "17:30", "18:30", "dinner", "Old", "Old", -27.473, 153.023)
                                ),
                                "Theme 2",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        )
                ),
                null
        );

        String instruction = retryInstructionForDay(
                req(List.of(), "relaxed"),
                2,
                List.of("day-2-stop-1-duplicate-poi-across-days"),
                draft
        );

        assertThat(instruction)
                .contains("Replace any stop that duplicates a POI already used on another day")
                .contains("mayReplace=City Botanic Gardens[morning,10:00-11:00]")
                .contains("Specifically replace these duplicates on day 2: City Botanic Gardens duplicates day 1 stop 1 (City Botanic Gardens)")
                .contains("mayRetime=none")
                .contains("mayInsertAround=none");
    }

    @Test
    void repairCrossDayDuplicatePoisReplacesDuplicateWhenDroppingWouldBreakDayStructure() {
        PlanDraftResponse draft = new PlanDraftResponse(
                "Brisbane",
                "Australia",
                2,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "relaxed",
                "Brisbane 2 day",
                "Overview",
                List.of(
                        new PlanDraftResponse.DayPlan(
                                1,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                                        place("Dinner Spot 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.472, 153.022)
                                ),
                                "Theme 1",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        ),
                        new PlanDraftResponse.DayPlan(
                                2,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 2", "restaurant", "lunch", "12:15", "13:15", "lunch", "Old", "Old", -27.471, 153.021),
                                        place("Dinner Spot 2", "restaurant", "dinner", "17:30", "18:30", "dinner", "Old", "Old", -27.473, 153.023)
                                ),
                                "Theme 2",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        )
                ),
                null
        );

        PlanDraftResponse repaired = service.repairCrossDayDuplicatePois(draft);

        assertThat(repaired.daysPlan().get(1).stops()).hasSize(3);
        assertThat(repaired.daysPlan().get(1).stops().getFirst().name()).isEqualTo("CBD Morning Garden Stroll");
        assertThat(repaired.daysPlan().get(1).stops().getFirst().category()).isEqualTo("walk");
        assertThat(service.validateDraft(repaired, req(List.of(), "relaxed")))
                .doesNotContain("day-2-stop-1-duplicate-poi-across-days");
    }

    @Test
    void repairCrossDayDuplicatePoisKeepsFallbackNamesUniqueAcrossMultipleDays() {
        PlanDraftResponse draft = new PlanDraftResponse(
                "Brisbane",
                "Australia",
                3,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "relaxed",
                "Brisbane 3 day",
                "Overview",
                List.of(
                        new PlanDraftResponse.DayPlan(
                                1,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.473, 153.023),
                                        place("Dinner Spot 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024)
                                ),
                                "Theme 1",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        ),
                        new PlanDraftResponse.DayPlan(
                                2,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "09:10", "10:10", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 2", "restaurant", "lunch", "12:10", "13:10", "lunch", "Old", "Old", -27.473, 153.023),
                                        place("Dinner Spot 2", "restaurant", "dinner", "18:10", "19:10", "dinner", "Old", "Old", -27.474, 153.024)
                                ),
                                "Theme 2",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        ),
                        new PlanDraftResponse.DayPlan(
                                3,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "09:20", "10:20", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 3", "restaurant", "lunch", "12:20", "13:20", "lunch", "Old", "Old", -27.473, 153.023),
                                        place("Dinner Spot 3", "restaurant", "dinner", "18:20", "19:20", "dinner", "Old", "Old", -27.474, 153.024)
                                ),
                                "Theme 3",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        )
                ),
                null
        );

        PlanDraftResponse repaired = service.repairCrossDayDuplicatePois(draft);

        String day2Replacement = repaired.daysPlan().get(1).stops().getFirst().name();
        String day3Replacement = repaired.daysPlan().get(2).stops().getFirst().name();

        assertThat(day2Replacement).isEqualTo("CBD Morning Garden Stroll");
        assertThat(day3Replacement).isNotEqualTo(day2Replacement);
        assertThat(day3Replacement).startsWith("CBD Morning Garden Stroll Alt");
        assertThat(service.validateDraft(repaired, new CreatePlanReq("Brisbane", 3, null, new CreatePlanReq.Party(2, 0), List.of(), "relaxed", "qwen-max", null)))
                .doesNotContain("duplicate-poi-across-days");
    }

    @Test
    void applyPostRouteRepairRepairsCrossDayDuplicatesDuringInitialPipeline() throws Exception {
        PlanDraftResponse draft = new PlanDraftResponse(
                "Brisbane",
                "Australia",
                2,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "relaxed",
                "Brisbane 2 day",
                "Overview",
                List.of(
                        new PlanDraftResponse.DayPlan(
                                1,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 1", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.473, 153.023),
                                        place("Dinner Spot 1", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024)
                                ),
                                "Theme 1",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        ),
                        new PlanDraftResponse.DayPlan(
                                2,
                                place("Hotel", "hotel", "night", "20:00", "21:00", null, "Hotel", "Hotel", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "09:10", "10:10", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 2", "restaurant", "lunch", "12:10", "13:10", "lunch", "Old", "Old", -27.473, 153.023),
                                        place("Dinner Spot 2", "restaurant", "dinner", "18:10", "19:10", "dinner", "Old", "Old", -27.474, 153.024)
                                ),
                                "Theme 2",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        )
                ),
                null
        );

        Method method = PlanProcessorService.class.getDeclaredMethod(
                "applyPostRouteRepair",
                PlanDraftResponse.class,
                CreatePlanReq.class,
                String.class,
                StringBuilder.class,
                StringBuilder.class
        );
        method.setAccessible(true);

        PlanDraftResponse repaired = (PlanDraftResponse) method.invoke(
                service,
                draft,
                new CreatePlanReq("Brisbane", 2, null, new CreatePlanReq.Party(2, 0), List.of(), "relaxed", "qwen-max", null),
                "initial",
                new StringBuilder(),
                new StringBuilder()
        );

        assertThat(service.validateDraft(repaired, new CreatePlanReq("Brisbane", 2, null, new CreatePlanReq.Party(2, 0), List.of(), "relaxed", "qwen-max", null)))
                .doesNotContain("day-2-stop-1-duplicate-poi-across-days");
        assertThat(repaired.daysPlan().get(1).stops().getFirst().name()).isEqualTo("CBD Morning Garden Stroll");
    }

    @Test
    void dayLevelRetryTreatsGooglePlacesNoMatchAsScopedRetryIssue() throws Exception {
        Method method = PlanProcessorService.class.getDeclaredMethod("shouldUseDayLevelPhasedRetry", CreatePlanReq.class, List.class);
        method.setAccessible(true);

        boolean shouldRetryByDay = (boolean) method.invoke(
                service,
                new CreatePlanReq("Brisbane", 3, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null),
                List.of("day-2-stop-3-google-places-no-match")
        );

        assertThat(shouldRetryByDay).isTrue();
    }

    @Test
    void dayLevelRetryTreatsGooglePlacesLowConfidenceAsScopedRetryIssue() throws Exception {
        Method method = PlanProcessorService.class.getDeclaredMethod("shouldUseDayLevelPhasedRetry", CreatePlanReq.class, List.class);
        method.setAccessible(true);

        boolean shouldRetryByDay = (boolean) method.invoke(
                service,
                new CreatePlanReq("Brisbane", 3, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null),
                List.of("day-2-stop-3-google-places-low-confidence")
        );

        assertThat(shouldRetryByDay).isTrue();
    }

    @Test
    void pruneUnselectedShoppingStopsRemovesShoppingWhenMarketShoppingNotSelected() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Queen Street Mall", "shop", "afternoon", "14:00", "15:00", null, "Shopping stop", "Shopping tip", -27.471, 153.021),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.472, 153.022),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
        ));

        PlanDraftResponse result = service.pruneUnselectedShoppingStops(draft, req(List.of("culture")));

        assertThat(result.daysPlan().getFirst().stops())
                .extracting(PlanDraftResponse.Place::name)
                .doesNotContain("Queen Street Mall");
    }

    @Test
    void repairMealStopsDropsReplaceableStrictMealIssueAndUsesEnsuredMeals() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Bad Lunch", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.470, 153.020),
                place("Museum", "museum", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.471, 153.021),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.472, 153.022)
        ));
        when(restaurantVerificationService.ensureRequiredMeals(any())).thenAnswer(invocation -> {
            PlanDraftResponse afterDrop = invocation.getArgument(0);
            List<PlanDraftResponse.Place> stops = new java.util.ArrayList<>(afterDrop.daysPlan().getFirst().stops());
            stops.add(1, place("Replacement Lunch", "restaurant", "lunch", "12:00", "13:00", "lunch", "New", "New", -27.473, 153.023));
            return withStops(afterDrop, stops);
        });

        PlanDraftResponse result = service.repairMealStops(draft, List.of("day-1-stop-1-google-places-no-match"));

        assertThat(result.daysPlan().getFirst().stops())
                .extracting(PlanDraftResponse.Place::name)
                .doesNotContain("Bad Lunch")
                .contains("Replacement Lunch");
    }

    @Test
    void validateDraftDetectsMissingRealMeals() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Park", "park", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("Dinner Park", "park", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
        ));

        List<String> issues = service.validateDraft(draft);

        assertThat(issues).contains("day-1-missing-real-lunch", "day-1-missing-real-dinner");
    }

    @Test
    void validateDraftDetectsCrossDayDuplicatePoi() {
        PlanDraftResponse draft = new PlanDraftResponse(
                "Brisbane",
                "Australia",
                2,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "normal",
                "Brisbane 2 day",
                "Overview",
                List.of(
                        new PlanDraftResponse.DayPlan(
                                1,
                                place("Hotel", "hotel", "night", "08:00", "09:00", null, "Hotel reason", "Hotel tip", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                                        place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.472, 153.022)
                                ),
                                "Theme 1",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        ),
                        new PlanDraftResponse.DayPlan(
                                2,
                                place("Hotel", "hotel", "night", "08:00", "09:00", null, "Hotel reason", "Hotel tip", -27.469, 153.019),
                                List.of(
                                        place("City Botanic Gardens", "park", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                                        place("Lunch Spot 2", "restaurant", "lunch", "12:15", "13:15", "lunch", "Old", "Old", -27.471, 153.021),
                                        place("Dinner Spot 2", "restaurant", "dinner", "17:30", "18:30", "dinner", "Old", "Old", -27.473, 153.023)
                                ),
                                "Theme 2",
                                "Morning",
                                "Afternoon",
                                "Evening",
                                "Note"
                        )
                ),
                null
        );
        CreatePlanReq req = new CreatePlanReq("Brisbane", 2, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null);

        List<String> issues = service.validateDraft(draft, req);

        assertThat(issues).contains("day-2-stop-1-duplicate-poi-across-days");
    }

    @Test
    void validateDraftSupportsRelaxAndFastPaceAliasesForNonMealMinimums() {
        PlanDraftResponse relaxDraft = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
        ), "relax");
        PlanDraftResponse fastDraft = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("City Garden", "park", "sunset", "16:00", "17:00", null, "Old", "Old", -27.473, 153.023),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.474, 153.024)
        ), "fast-pace");

        List<String> relaxIssues = service.validateDraft(relaxDraft);
        List<String> fastIssues = service.validateDraft(fastDraft);

        assertThat(relaxIssues).doesNotContain("day-1-too-few-non-meal-stops");
        assertThat(fastIssues).doesNotContain("day-1-too-few-non-meal-stops");
    }

    @Test
    void validateDraftUsesSkeletonEffectiveMinBeforeDefaultPaceMin() {
        PlanDraftResponse fastDraftWithSparseSkeleton = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.472, 153.022)
        ), "fast-pace");
        CreatePlanReq fastReq = new CreatePlanReq("Brisbane", 1, null, new CreatePlanReq.Party(2, 0), List.of("culture"), "fast-pace", "qwen-max", null);

        List<String> issues = service.validateDraft(fastDraftWithSparseSkeleton, fastReq);

        assertThat(issues).doesNotContain("day-1-too-few-non-meal-stops");
    }

    @Test
    void validateDraftSoftensMissingMarketShoppingWhenThemeParkRemains() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Dreamworld Theme Park (Morning)", "theme_park", "morning", "09:00", "12:00", null, "Old", "Old", -27.864, 153.316),
                place("Dreamworld Theme Park Internal Dining Break", "dining", "lunch", "12:20", "13:20", "lunch", "Lunch", "Lunch tip", -27.864, 153.316),
                place("Dreamworld Theme Park (Afternoon)", "theme_park", "afternoon", "13:20", "16:30", null, "Old", "Old", -27.864, 153.316),
                place("Dinner Spot", "restaurant", "dinner", "17:30", "19:00", "dinner", "Old", "Old", -27.867, 153.318)
        ));

        List<String> issues = service.validateDraft(draft, req(List.of("theme_park", "market_shopping")));

        assertThat(issues).doesNotContain("style-missing-market-shopping");
    }

    @Test
    void validateDraftKeepsMissingMarketShoppingHardWhenNoThemeParkSelected() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Museum", "museum", "morning", "10:00", "11:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
        ));

        List<String> issues = service.validateDraft(draft, req(List.of("market_shopping")));

        assertThat(issues).contains("style-missing-market-shopping");
    }

    @Test
    void expandThemeParkDiningBreaksSplitsThemeParkAroundLunch() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Dreamworld Theme Park", "theme_park", "morning", "09:00", "12:00", null, "Old", "Old", -27.864, 153.316),
                place("Lunch Spot", "restaurant", "lunch", "12:30", "13:30", "lunch", "Old", "Old", -27.864, 153.316),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.470, 153.020)
        ), "relaxed");

        PlanDraftResponse result = service.expandThemeParkDiningBreaks(draft);

        assertThat(result.daysPlan().getFirst().stops())
                .extracting(PlanDraftResponse.Place::name)
                .contains("Dreamworld Theme Park (Afternoon)", "Dreamworld Theme Park Internal Dining Break");
    }

    @Test
    void clampOversizedGapsExtendsThemeParkContinuationBeforeDinner() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Dreamworld Theme Park (Morning)", "theme_park", "morning", "09:00", "12:00", null, "Old", "Old", -27.864, 153.316),
                place("Dreamworld Theme Park Internal Dining Break", "dining", "lunch", "12:20", "13:20", "lunch", "Lunch", "Lunch tip", -27.864, 153.316),
                place("Dreamworld Theme Park (Afternoon)", "theme_park", "afternoon", "13:20", "14:20", null, "Old", "Old", -27.864, 153.316),
                place("Dinner Spot", "restaurant", "dinner", "17:30", "19:00", "dinner", "Old", "Old", -27.867, 153.318)
        ));

        PlanDraftResponse result = service.clampOversizedGaps(draft);

        PlanDraftResponse.Place continuation = result.daysPlan().getFirst().stops().get(2);
        PlanDraftResponse.Place dinner = result.daysPlan().getFirst().stops().get(3);
        assertThat(continuation.startTime()).isEqualTo("13:20");
        assertThat(continuation.endTime()).isEqualTo("15:30");
        assertThat(continuation.stayMinutes()).isEqualTo(130);
        assertThat(dinner.startTime()).isEqualTo("17:30");
    }

    @Test
    void pruneFlexibleFoodStopsKeepsThemeParkDiningLunchAsMeal() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Dreamworld Theme Park", "theme_park", "morning", "09:00", "12:00", null, "Old", "Old", -27.864, 153.316),
                place("Dreamworld Theme Park Dining Break", "dining", "lunch", "12:00", "13:00", "lunch", "Lunch", "Lunch tip", -27.864, 153.316),
                place("Dreamworld Theme Park (Afternoon)", "theme_park", "afternoon", "13:00", "17:00", null, "Old", "Old", -27.864, 153.316),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.470, 153.020)
        ));

        PlanDraftResponse result = service.pruneFlexibleFoodStops(draft);

        PlanDraftResponse.Place lunch = result.daysPlan().getFirst().stops().stream()
                .filter(stop -> "Dreamworld Theme Park Dining Break".equals(stop.name()))
                .findFirst()
                .orElseThrow();
        assertThat(lunch.category()).isEqualTo("dining");
        assertThat(lunch.timeSlot()).isEqualTo("lunch");
        assertThat(lunch.mealType()).isEqualTo("lunch");
        assertThat(service.validateDraft(result)).doesNotContain("day-1-missing-lunch");
    }

    @Test
    void normalizeDraftScheduleWithRouteDurationsUsesMapServiceTransferMinutes() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Gallery Stop", "attraction", "morning", "09:00", "10:00", null, "Old", "Old", -27.470, 153.020),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.471, 153.021),
                place("River View", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", -27.472, 153.022),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.473, 153.023)
        ));
        when(mapService.walk_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("800", "600"));
        when(mapService.transit_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("800", "1200"));
        when(mapService.car_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("800", "900"));

        PlanDraftResponse result = service.normalizeDraftScheduleWithRouteDurations(draft);

        assertThat(result.daysPlan().getFirst().stops().get(1).startTime()).isEqualTo("12:15");
        verify(mapService, atLeastOnce()).walk_summary(anyString(), anyString());
        verify(mapService, atLeastOnce()).transit_summary(anyString(), anyString());
        verify(mapService, atLeastOnce()).car_summary(anyString(), anyString());
    }

    @Test
    void resolveStopCoordinatesInParallelDeduplicatesWithinRequest() {
        PlanDraftResponse.Place shared = place("Shared Stop", "attraction", "morning", "09:00", "10:00", null, "Old", "Old", null, null);
        PlanDraftResponse.Place another = place("Another Stop", "attraction", "afternoon", "14:00", "15:00", null, "Old", "Old", null, null);
        List<PlanDraftResponse.Place> stops = List.of(shared, shared, another);

        PlanProcessorService spyService = spy(service);
        doReturn(new StopCoordinate(-27.470, 153.020)).when(spyService).resolveStopCoordinateSafely(shared);
        doReturn(new StopCoordinate(-27.480, 153.030)).when(spyService).resolveStopCoordinateSafely(another);

        List<StopCoordinate> coordinates = spyService.resolveStopCoordinatesInParallel(stops);

        assertThat(coordinates).hasSize(3);
        verify(spyService, times(2)).resolveStopCoordinateSafely(any());
    }

    @Test
    void normalizeDraftScheduleWithRouteDurationsFallsBackToExistingCoordinatesWhenRefreshFails() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Gallery of Modern Art", "museum", "morning", "09:00", "10:00", null, "Old", "Old", -27.470560, 153.017133),
                place("Queensland Museum", "museum", "afternoon", "11:00", "12:00", null, "Old", "Old", -27.473109, 153.018189),
                place("Lunch Spot", "restaurant", "lunch", "12:30", "13:30", "lunch", "Old", "Old", -27.475467, 153.020530),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.477345, 153.020502)
        ));
        when(mapService.geocode(anyString(), anyString())).thenReturn(null);
        when(mapService.walk_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("600", "500"));
        when(mapService.transit_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("600", "700"));
        when(mapService.car_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("600", "650"));

        service.normalizeDraftScheduleWithRouteDurations(draft);

        verify(mapService, atLeastOnce()).walk_summary(anyString(), anyString());
        verify(mapService, atLeastOnce()).transit_summary(anyString(), anyString());
        verify(mapService, atLeastOnce()).car_summary(anyString(), anyString());
    }

    @Test
    void normalizeDraftScheduleWithRouteDurationsCachesRouteLookupsPerOriginDestination() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Anchor A", "attraction", "morning", "09:00", "10:00", null, "Old", "Old", -27.470000, 153.020000),
                place("Anchor B", "attraction", "morning", "10:20", "11:20", null, "Old", "Old", -27.480000, 153.030000),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.470000, 153.020000),
                place("Anchor B Return", "attraction", "afternoon", "13:40", "14:40", null, "Old", "Old", -27.480000, 153.030000),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.470000, 153.020000)
        ));

        when(mapService.walk_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1500"));
        when(mapService.transit_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1800"));
        when(mapService.car_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1600"));

        service.normalizeDraftScheduleWithRouteDurations(draft);

        // There are 4 transitions but only 2 unique directed OD pairs in this route.
        verify(mapService, times(2)).walk_summary(anyString(), anyString());
        verify(mapService, times(2)).transit_summary(anyString(), anyString());
        verify(mapService, times(2)).car_summary(anyString(), anyString());
    }

    @Test
    void normalizeDraftScheduleWithRouteDurationsReusesCrossRequestRouteCacheAcrossCalls() {
        PlanDraftResponse draft = validDraft(List.of(
                place("Anchor A", "attraction", "morning", "09:00", "10:00", null, "Old", "Old", -27.470000, 153.020000),
                place("Anchor B", "attraction", "morning", "10:20", "11:20", null, "Old", "Old", -27.480000, 153.030000),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.470000, 153.020000),
                place("Anchor B Return", "attraction", "afternoon", "13:40", "14:40", null, "Old", "Old", -27.480000, 153.030000),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.470000, 153.020000)
        ));

        when(mapService.walk_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1500"));
        when(mapService.transit_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1800"));
        when(mapService.car_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1600"));

        service.normalizeDraftScheduleWithRouteDurations(draft);
        service.normalizeDraftScheduleWithRouteDurations(draft);

        // Baseline: second run reuses cross-request cache and does not issue additional route-summary calls.
        verify(mapService, times(2)).walk_summary(anyString(), anyString());
        verify(mapService, times(2)).transit_summary(anyString(), anyString());
        verify(mapService, times(2)).car_summary(anyString(), anyString());
    }

    @Test
    void normalizeDraftScheduleWithRouteDurationsUsesRedisL2AfterLocalCacheClear() {
        java.util.Map<String, String> redisStore = new java.util.concurrent.ConcurrentHashMap<>();
        lenient().doAnswer(invocation -> redisStore.get(invocation.getArgument(0)))
                .when(valueOperations).get(anyString());
        lenient().doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(java.time.Duration.class));

        PlanDraftResponse draft = validDraft(List.of(
                place("Anchor A", "attraction", "morning", "09:00", "10:00", null, "Old", "Old", -27.470000, 153.020000),
                place("Anchor B", "attraction", "morning", "10:20", "11:20", null, "Old", "Old", -27.480000, 153.030000),
                place("Lunch Spot", "restaurant", "lunch", "12:00", "13:00", "lunch", "Old", "Old", -27.470000, 153.020000),
                place("Anchor B Return", "attraction", "afternoon", "13:40", "14:40", null, "Old", "Old", -27.480000, 153.030000),
                place("Dinner Spot", "restaurant", "dinner", "18:00", "19:00", "dinner", "Old", "Old", -27.470000, 153.020000)
        ));

        when(mapService.walk_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1500"));
        when(mapService.transit_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1800"));
        when(mapService.car_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1600"));

        service.normalizeDraftScheduleWithRouteDurations(draft);
        service.clearRouteChoiceLocalCacheOnly();
        service.normalizeDraftScheduleWithRouteDurations(draft);

        // Baseline: second run should be served by Redis L2 and avoid extra map summary calls.
        verify(mapService, times(2)).walk_summary(anyString(), anyString());
        verify(mapService, times(2)).transit_summary(anyString(), anyString());
        verify(mapService, times(2)).car_summary(anyString(), anyString());
        verify(valueOperations, atLeastOnce()).set(anyString(), anyString(), any(java.time.Duration.class));
        verify(valueOperations, atLeastOnce()).get(anyString());
    }

    @Test
    void applyPostRouteRepairOnlyReschedulesAndRewritesChangedDays() throws Exception {
        PlanDraftResponse draft = multiDayDraft(2, List.of(
                dayPlan(1, List.of(
                        place("Museum A", "museum", "morning", "10:00", "11:00", null, "Original museum A", "Original tip A", -27.470000, 153.020000),
                        place("Lunch A", "restaurant", "lunch", "12:15", "13:15", "lunch", "Lunch A", "Lunch tip A", -27.471000, 153.021000),
                        place("Arcade A", "shopping", "afternoon", "13:40", "14:10", null, "Original shopping A", "Original shopping tip A", -27.480000, 153.030000),
                        place("Park A", "park", "sunset", "14:30", "15:15", null, "Original park A", "Original park tip A", -27.490000, 153.040000),
                        place("Dinner A", "restaurant", "dinner", "18:00", "19:00", "dinner", "Dinner A", "Dinner tip A", -27.500000, 153.050000)
                )),
                dayPlan(2, List.of(
                        place("Museum B", "museum", "morning", "10:00", "11:00", null, "Keep museum B", "Keep tip B", -27.570000, 153.120000),
                        place("Lunch B", "restaurant", "lunch", "12:15", "13:15", "lunch", "Lunch B", "Lunch tip B", -27.571000, 153.121000),
                        place("Gallery B", "gallery", "afternoon", "13:40", "14:40", null, "Keep gallery B", "Keep gallery tip B", -27.580000, 153.130000),
                        place("Park B", "park", "sunset", "15:00", "16:40", null, "Keep park B", "Keep park tip B", -27.585000, 153.135000),
                        place("Dinner B", "restaurant", "dinner", "17:30", "18:30", "dinner", "Dinner B", "Dinner tip B", -27.590000, 153.140000)
                ))
        ), "normal");

        when(mapService.walk_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1500"));
        when(mapService.transit_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1800"));
        when(mapService.car_summary(anyString(), anyString())).thenReturn(new MapService.RouteSummary("900", "1600"));

        Method method = PlanProcessorService.class.getDeclaredMethod(
                "applyPostRouteRepair",
                PlanDraftResponse.class,
                CreatePlanReq.class,
                String.class,
                StringBuilder.class,
                StringBuilder.class
        );
        method.setAccessible(true);

        PlanDraftResponse result = (PlanDraftResponse) method.invoke(
                service,
                draft,
                new CreatePlanReq("Brisbane", 2, null, new CreatePlanReq.Party(2, 0), List.of(), "normal", "qwen-max", null),
                "initial",
                new StringBuilder(),
                new StringBuilder()
        );

        assertThat(result.daysPlan().get(0).stops())
                .extracting(PlanDraftResponse.Place::name)
                .doesNotContain("Arcade A");
        assertThat(result.daysPlan().get(1).stops().get(0).reason()).isEqualTo("Keep museum B");

        // Only the changed first day is rescheduled: 3 remaining transitions * 3 travel modes.
        verify(mapService, times(3)).walk_summary(anyString(), anyString());
        verify(mapService, times(3)).transit_summary(anyString(), anyString());
        verify(mapService, times(3)).car_summary(anyString(), anyString());
    }

    private void passThroughVerification() {
        when(hotelVerificationService.verifyAndNormalize(any())).thenAnswer(invocation ->
                new HotelVerificationService.VerificationResult(invocation.getArgument(0), List.of()));
        when(restaurantVerificationService.verifyAndNormalize(any())).thenAnswer(invocation ->
                new RestaurantVerificationService.VerificationResult(invocation.getArgument(0), List.of()));
        when(restaurantVerificationService.ensureRequiredMeals(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CreatePlanReq req(List<String> style) {
        return new CreatePlanReq("Brisbane", 1, null, new CreatePlanReq.Party(2, 0), style, "normal", "qwen-max", null);
    }

    private CreatePlanReq req(List<String> style, String pace) {
        return new CreatePlanReq("Brisbane", 1, null, new CreatePlanReq.Party(2, 0), style, pace, "qwen-max", null);
    }

    private PlanDraftResponse validDraft(List<PlanDraftResponse.Place> stops) {
        return validDraft(stops, "normal");
    }

    private PlanDraftResponse validDraft(List<PlanDraftResponse.Place> stops, String pace) {
        return new PlanDraftResponse(
                "Brisbane",
                "Australia",
                1,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                pace,
                "Brisbane day",
                "Overview",
                List.of(new PlanDraftResponse.DayPlan(
                        1,
                        place("Hotel", "hotel", "morning", "08:00", "09:00", null, "Hotel reason", "Hotel tip", -27.469, 153.019),
                        stops,
                        "Theme",
                        "Morning",
                        "Afternoon",
                        "Evening",
                        "Note"
                )),
                null
        );
    }

    private PlanDraftResponse multiDayDraft(int days, List<PlanDraftResponse.DayPlan> dayPlans, String pace) {
        return new PlanDraftResponse(
                "Brisbane",
                "Australia",
                days,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                pace,
                "Brisbane day",
                "Overview",
                dayPlans,
                null
        );
    }

    private PlanDraftResponse.DayPlan dayPlan(int dayIndex, List<PlanDraftResponse.Place> stops) {
        return new PlanDraftResponse.DayPlan(
                dayIndex,
                place("Hotel " + dayIndex, "hotel", "morning", "08:00", "09:00", null, "Hotel reason", "Hotel tip", -27.469 + (dayIndex * 0.001), 153.019 + (dayIndex * 0.001)),
                stops,
                "Theme",
                "Morning",
                "Afternoon",
                "Evening",
                "Note"
        );
    }

    private PlanDraftResponse withStops(PlanDraftResponse draft, List<PlanDraftResponse.Place> stops) {
        PlanDraftResponse.DayPlan day = draft.daysPlan().getFirst();
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                List.of(new PlanDraftResponse.DayPlan(
                        day.dayIndex(),
                        day.hotel(),
                        stops,
                        day.theme(),
                        day.morningNote(),
                        day.afternoonNote(),
                        day.eveningNote(),
                        day.note()
                )),
                draft.copyPolishStatus()
        );
    }

    private PlanDraftResponse.Place place(
            String name,
            String category,
            String timeSlot,
            String start,
            String end,
            String mealType,
            String reason,
            String tip,
            Double lat,
            Double lon
    ) {
        return new PlanDraftResponse.Place(
                name,
                name + " address",
                "CBD",
                "Brisbane",
                "QLD",
                "4000",
                "Australia",
                category,
                stayMinutes(start, end),
                timeSlot,
                start,
                end,
                mealType,
                null,
                null,
                null,
                null,
                reason,
                tip,
                null,
                null,
                "OPERATIONAL",
                null,
                lat,
                lon
        );
    }

    private Integer stayMinutes(String start, String end) {
        String[] startParts = start.split(":");
        String[] endParts = end.split(":");
        int startMinutes = Integer.parseInt(startParts[0]) * 60 + Integer.parseInt(startParts[1]);
        int endMinutes = Integer.parseInt(endParts[0]) * 60 + Integer.parseInt(endParts[1]);
        return endMinutes - startMinutes;
    }

    private String retryInstructionForDay(
            CreatePlanReq req,
            int dayIndex,
            List<String> dayIssues,
            PlanDraftResponse draft
    ) throws Exception {
        Method skeletonContextMethod = PlanProcessorService.class.getDeclaredMethod(
                "skeletonContext",
                CreatePlanReq.class,
                PlanDraftResponse.class
        );
        skeletonContextMethod.setAccessible(true);
        Object skeletonContext = skeletonContextMethod.invoke(service, req, draft);
        Method method = PlanProcessorService.class.getDeclaredMethod(
                "retryInstructionForDay",
                int.class,
                List.class,
                PlanDraftResponse.class,
                skeletonContext.getClass()
        );
        method.setAccessible(true);
        return (String) method.invoke(service, dayIndex, dayIssues, draft, skeletonContext);
    }

    private String retryInstruction(
            CreatePlanReq req,
            List<String> validationIssues,
            PlanDraftResponse draft
    ) throws Exception {
        Method skeletonContextMethod = PlanProcessorService.class.getDeclaredMethod(
                "skeletonContext",
                CreatePlanReq.class,
                PlanDraftResponse.class
        );
        skeletonContextMethod.setAccessible(true);
        Object skeletonContext = skeletonContextMethod.invoke(service, req, draft);
        Method method = PlanProcessorService.class.getDeclaredMethod(
                "retryInstruction",
                CreatePlanReq.class,
                List.class,
                skeletonContext.getClass()
        );
        method.setAccessible(true);
        return (String) method.invoke(service, req, validationIssues, skeletonContext);
    }
}
