package thesis.project.gu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import thesis.project.gu.client.GooglePlacesClient;
import thesis.project.gu.req.CreatePlanReq;
import thesis.project.gu.response.PlanDraftResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private ObjectMapper objectMapper;
    private PlanProcessorService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new PlanProcessorService(
                aiService,
                cacheSerive,
                objectMapper,
                hotelVerificationService,
                restaurantVerificationService,
                mapService,
                googlePlacesClient
        );
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

        PlanDraftResponse result = service.processDraft(req(List.of()), objectMapper.writeValueAsString(verified), false, 0);

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

        PlanDraftResponse result = service.processDraft(req(List.of("theme_park")), objectMapper.writeValueAsString(verified), false, 0);

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

        PlanDraftResponse result = service.processDraft(req(List.of()), objectMapper.writeValueAsString(verified), false, 0);

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

        PlanDraftResponse result = service.processDraft(req(List.of("market_shopping")), objectMapper.writeValueAsString(verified), false, 0);

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
        when(aiService.regeneratePlanRaw(any(), anyString())).thenReturn(objectMapper.writeValueAsString(retry));
        when(aiService.polishPlanCopy(any())).thenReturn(TripAiService.CopyPolishResult.empty("disabled"));

        PlanDraftResponse result = service.processDraft(req(List.of()), objectMapper.writeValueAsString(invalidInitial), false, 0);

        List<String> names = result.daysPlan().getFirst().stops().stream().map(PlanDraftResponse.Place::name).toList();
        assertThat(names).doesNotContain("Shopping Mall");
        PlanDraftResponse.Place museum = result.daysPlan().getFirst().stops().getFirst();
        assertThat(museum.reason()).contains("main cultural block");
        assertThat(result.copyPolishStatus()).isEqualTo("fallback-disabled");
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
}
