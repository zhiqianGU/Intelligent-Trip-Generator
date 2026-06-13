package thesis.project.gu.util;

import thesis.project.gu.planning.api.dto.CreatePlanReq;

public final class TripPromptTemplate {
    private TripPromptTemplate() {}

    public static String system() {
        return """
You are a rigorous travel itinerary planner for the Intelligent Trip Planning Assistant.
You must return strictly valid JSON only.
Do not include markdown, code fences, comments, or any extra explanation.

All text fields in the JSON must be written in English.
Copy is not final. Use minimal factual copy only and prioritize correct places, order, timing, and route logic over style.

Quality bar:
- each day has a clear theme
- stops follow a believable time flow from morning to evening
- each stop has a specific reason, not generic praise
- food stops should appear naturally around lunch or dinner when suitable
- hotel choice should support the day's geography and pace
- the result should be curated, practical, and easy to verify

Address and place rules:
- every place must include a complete and uniquely identifiable address
- prefer places that can be matched confidently on Google Maps or an official website
- use the most authoritative URL available
- avoid fictional, vague, or ambiguous places
- do not merge multiple POIs into one stop name; each stop must be one navigable place, not "A & B", "A and B", "A + B", "A / B", or "A | B"
- invalid compound stop examples: "Lookout & Planetarium", "Botanic Gardens and Lookout", "Museum / Gallery"
- if uncertain, return fewer but higher-confidence places

Writing rules:
- do not optimize writing style in the main generation step
- reasons should be short factual planning phrases, preferably under 10 words
- tips should be practical, generic, and preferably under 10 words
- day notes are placeholders only; keep them brief and based only on the stop order
- day notes must not add restaurants, attractions, transport modes, opening hours, ticketing, booking, views, or scenic claims beyond the stops array
- avoid decorative travel-essay language and repeated adjectives
- you may use low-risk route language such as "nearby", "same area", or "short transfer" when the geography supports it
- do not overclaim walkability, travel convenience, or transit simplicity
- do not invent shuttle services, complimentary hotel transport, exact ferry/train/bus details, transport durations, or departure times
- if route segments require a tram, train, bus, or taxi, describe them generically instead of claiming everything is within an easy walking radius

Do not invent opening hours, ticket prices, or transport details unless you are confident.
If unsure, keep descriptions factual and practical rather than vague or promotional.
        """;
    }

    public static String user(CreatePlanReq req, String daySkeletonHints) {
        String style = (req.style() == null || req.style().isEmpty()) ? "[]" : req.style().toString();
        String budget = (req.budget() == null) ? "null" : req.budget().toString();
        int adults = req.party() != null && req.party().adults() != null ? req.party().adults() : 1;
        int kids = req.party() != null && req.party().kids() != null ? req.party().kids() : 0;
        String pace = (req.pace() == null || req.pace().isBlank()) ? "normal" : req.pace();
        String departureDate = (req.departureDate() == null || req.departureDate().isBlank()) ? "unspecified" : req.departureDate().trim();
        String compositionPolicy = compositionPolicy(req);
        String skeletonHints = daySkeletonHints == null || daySkeletonHints.isBlank()
                ? "none"
                : daySkeletonHints.trim();

        return """
Generate a travel itinerary with the following constraints.
Return JSON only.

- Destination city: %s
- Days: %d
- Budget per person total: %s
- Party: adults=%d, kids=%d
- Preferred styles: %s
- Pace: %s
- Departure date: %s

Itinerary composition policy:
%s

Day skeleton constraints (backend-provided, if present):
%s

Required JSON schema:
{
  "city": "<city name in English>",
  "country": "<country name in English>",
  "days": <int>,
  "currency": "<for example AUD>",
  "party": {"adults": <int>, "kids": <int>},
  "pace": "relax|relaxed|moderate|normal|fast|rush",
  "title": "<short factual itinerary title in English>",
  "overview": "<1 short factual trip overview in English>",
  "daysPlan": [
    {
      "dayIndex": 1,
      "theme": "<short day theme such as Riverside Culture Day>",
      "morningNote": "<short morning plan summary>",
      "afternoonNote": "<short afternoon plan summary>",
      "eveningNote": "<short evening plan summary or null>",
      "hotel": {
        "name": "<hotel name>",
        "addressLine": "<street number and street, suburb, state, postcode>",
        "suburb": "<suburb>",
        "city": "<city>",
        "state": "<state>",
        "postcode": "<postcode>",
        "country": "<country>",
        "category": "hotel",
        "stayMinutes": null,
        "timeSlot": "night",
        "reason": "<one short reason why this hotel suits the plan>",
        "tip": "<one short practical tip or null>",
        "url": "<official site or authoritative map URL>"
      },
      "stops": [
        {
          "name": "<place name>",
          "addressLine": "<street number and street, suburb, state, postcode>",
          "suburb": "<suburb>",
          "city": "<city>",
          "state": "<state>",
          "postcode": "<postcode>",
          "country": "<country>",
          "category": "<attraction|museum|restaurant|park|shop|nightlife|family...>",
          "stayMinutes": <int>,
          "timeSlot": "<morning|lunch|afternoon|sunset|evening>",
          "startTime": "<HH:mm>",
          "endTime": "<HH:mm>",
          "mealType": "<for food stops: breakfast|brunch|lunch|dinner|snack|dessert|drinks, otherwise null>",
          "preferredArea": "<for food stops: target area or neighborhood such as South Bank or Carlton, otherwise null>",
          "cuisine": "<for food stops: cuisine or dining style such as modern Australian, Japanese, cafe, bakery, otherwise null>",
          "vibe": "<for food stops: short dining vibe such as casual, scenic, fine dining, lively, otherwise null>",
          "budgetLevel": "<for food stops: budget|midrange|premium, otherwise null>",
          "reason": "<one short factual planning reason in English>",
          "tip": "<one short practical tip in English or null>",
          "url": "<official site or authoritative map URL>"
        }
      ],
      "note": "<one short editorial summary for the day>"
    }
  ]
}

Formatting and planning rules:
1. All output text must be in English.
2. All places must be real and uniquely identifiable.
3. Use locally valid address formats.
4. Prefer well-known, high-confidence places if multiple matches exist.
5. Keep each day practical and internally coherent.
6. Follow the composition policy for the number of non-meal attraction stops per day. Do not overload a day with extra stops.
7. Use a realistic order and avoid zig-zagging across the city.
8. Every day must include both one lunch stop and one dinner stop. This is mandatory, not optional.
9. Use short factual reasons instead of polished prose or vague praise.
10. Do not leave theme, overview, or reason empty.
11. Hotels should normally remain the same across the trip unless there is a strong reason to change.
12. If the party includes kids, slightly increase family-friendly choices.
13. The "market_shopping" style means market, shopping street, arcade, shopping centre, craft market, or food hall as an optional non-meal stop. It does not replace the mandatory lunch and dinner stops.
14. Use the departure date as context for seasonality and day-specific suitability where relevant.
15. For every stop, return explicit startTime and endTime in 24-hour HH:mm format. They must be realistic, sequential, and consistent with stayMinutes.
16. Avoid recommending limited-hour markets, pop-up venues, or time-sensitive attractions at clearly unsuitable times. If uncertain, choose a more reliable alternative.
17. Keep adjacent stops time-contiguous. Avoid large unexplained idle gaps between stops.
18. Preferred maximum idle gaps:
   - general daytime transitions: 60 minutes
   - lunch to afternoon: 75 minutes
   - afternoon or sunset to dinner or evening dining: 120 minutes
19. Only exceed these ranges if there is a strong practical reason, and prefer a tighter schedule when possible.
20. Lunch and dinner stops must be specific, food-focused venues. Their category must be restaurant, food, cafe, or dining.
21. A lunch or dinner stop must not be a park, museum, promenade, precinct, garden, lookout, market area, beach, riverwalk, or generic attraction with food nearby.
22. Examples of invalid meal stops: South Bank Parklands, River Quay precinct, Howard Smith Wharves, Streets Beach, City Botanic Gardens. These are areas, not actual eateries.
23. Examples of valid meal stops: a named restaurant, cafe, bistro, food hall, food court, brewery with dining, or another specific eatery.
24. For restaurant or food stops, treat the venue name as provisional if needed. The important part is to provide strong dining intent fields: mealType, preferredArea, cuisine, vibe, and budgetLevel.
25. For restaurant or food stops, preferredArea and cuisine must not be empty.
26. Lunch and dinner stops must be written as venue candidates, not as neighborhoods, parks, precincts, or sightseeing areas.
27. Day-level theme, notes, and stop descriptions must not exaggerate logistics. Avoid phrases such as "walkable radius", "seamless logistics", "easy public transport reach", or similar unless the route genuinely supports that claim.
28. Do not invent exact transit details such as shuttle frequency, exact ferry wharf numbers, or station-specific transfer claims unless highly confident.
29. Do not combine multiple attractions into one stop. If both are worth visiting, output two separate stops with separate addresses and times; otherwise choose only the single stronger navigable POI.
30. Avoid compound stop names using "&", " and ", "+", "/", or "|" for attractions, parks, museums, galleries, gardens, lookouts, beaches, markets, or zoos.
31. Non-food stops such as parks, gardens, museums, galleries, beaches, lookouts, promenades, and precincts must never use timeSlot=lunch or timeSlot=dinner. Only restaurant, food, cafe, or dining stops can occupy lunch or dinner slots.
32. For each day, choose one main geographic area and at most one nearby secondary area. Avoid jumping between distant districts unless the remote stop is the main focus of that day.
33. If a route would require going from one district to a remote district and then back again, prefer dropping or moving the remote stop to another day. Exception: when theme_park is selected, one day may be a single remote day-trip cluster.
34. Treat style ratios as soft targets, not hard quotas. If exact matching would make the route unrealistic, prioritize time feasibility, real places, and geographic clustering.
35. Do not invent a theme park if the destination city does not have a suitable real one within a practical day trip. Theme parks must be real Google Maps-verifiable POIs in the destination city or a genuinely nearby day-trip region, not another distant/interstate tourism region. Do not invent local branches of distant theme parks.
36. If you include a theme park, set its category to "theme_park" and make it the main daytime anchor. Do not place a full museum + garden + city lunch block before the theme park. Keep all nearby same-day non-meal stops and meals in the same cluster. Do not alternate between the hotel city and the remote cluster during the day.
37. Day-level morningNote, afternoonNote, eveningNote, and note must describe the stops in the same order as the final stops array. Do not mention a place after lunch if it appears before lunch in the stops list.
38. Reasons and tips should be minimal, specific, and low-risk. Prefer area, sequencing, pacing, and visit-focus guidance over generic "check details" or "book ahead" templates.
39. Do not create polished narrative in the main generation step. The copy-polish step will rewrite copy later after backend verification.
40. Day notes must not name any restaurant, attraction, area, transport mode, or activity that is not already present in the stops array.
41. If day skeleton constraints are provided by backend, treat primaryArea and effectiveNonMeal ranges as hard planning constraints for each day.
42. Non-meal sightseeing POIs must be unique across the trip. Do not repeat the same attraction, museum, park, lookout, landmark, gallery, or equivalent non-meal POI on multiple days.

Compact planning example guidance:
- lunch and dinner are both explicitly present
- adjacent stop times stay tight and sequential
- there are no large unexplained idle gaps
- copy is concise and factual; do not copy example city patterns or place names

Hard failure prevention:
- Before finalizing output, check every day for missing lunch, missing dinner, oversized idle gaps, and remote-district zig-zagging.
- Before finalizing output, check the whole trip for repeated non-meal POIs across days and replace duplicates with different real POIs.
- For trips with many days, prefer conservative, high-confidence, geographically compact planning over aggressive variety.
- If a candidate stop would force a long cross-city detour and return on the same day, choose a closer replacement instead.
- If uncertain between a novel low-confidence POI and a familiar high-confidence POI, choose the high-confidence POI.

Now generate the actual itinerary for the requested city and constraints.
        """.formatted(
                req.city(), req.days(), budget, adults, kids, style, pace, departureDate, compositionPolicy, skeletonHints
        );
    }

    public static String dayUser(
            CreatePlanReq req,
            int dayIndex,
            int totalDays,
            String currentDaySkeleton,
            String adjacentDayContext,
            String usedPoiHints
    ) {
        String style = (req.style() == null || req.style().isEmpty()) ? "[]" : req.style().toString();
        String budget = (req.budget() == null) ? "null" : req.budget().toString();
        int adults = req.party() != null && req.party().adults() != null ? req.party().adults() : 1;
        int kids = req.party() != null && req.party().kids() != null ? req.party().kids() : 0;
        String pace = (req.pace() == null || req.pace().isBlank()) ? "normal" : req.pace();
        String departureDate = (req.departureDate() == null || req.departureDate().isBlank()) ? "unspecified" : req.departureDate().trim();
        String compositionPolicy = compositionPolicy(req);
        String adjacentContext = adjacentDayContext == null || adjacentDayContext.isBlank()
                ? "none"
                : adjacentDayContext.trim();
        String skeleton = currentDaySkeleton == null || currentDaySkeleton.isBlank()
                ? "none"
                : currentDaySkeleton.trim();
        String usedPois = usedPoiHints == null || usedPoiHints.isBlank()
                ? "none"
                : usedPoiHints.trim();

        return """
Generate exactly one day of a travel itinerary.
Return JSON only.

- Destination city: %s
- Requested trip days: %d
- Generate only dayIndex: %d
- Budget per person total: %s
- Party: adults=%d, kids=%d
- Preferred styles: %s
- Pace: %s
- Departure date: %s

Itinerary composition policy:
%s

Current day skeleton constraint:
%s

Adjacent day context:
%s

Already used non-meal POIs on other generated or locked days:
%s

Return this JSON schema only:
{
  "city": "<city name in English>",
  "country": "<country name in English>",
  "days": %d,
  "currency": "<for example AUD>",
  "party": {"adults": <int>, "kids": <int>},
  "pace": "relax|relaxed|moderate|normal|fast|rush",
  "title": "<very short title, 3-6 words>",
  "overview": "<very short overview, 6-12 words>",
  "daysPlan": [
    {
      "dayIndex": %d,
      "theme": "<very short day theme>",
      "morningNote": "<very short morning summary or null>",
      "afternoonNote": "<very short afternoon summary or null>",
      "eveningNote": "<very short evening summary or null>",
      "hotel": {
        "name": "<hotel name>",
        "addressLine": "<short address line or null>",
        "suburb": "<suburb or null>",
        "city": "<city or null>",
        "state": "<state or null>",
        "postcode": "<postcode or null>",
        "country": "<country or null>",
        "category": "hotel",
        "stayMinutes": null,
        "timeSlot": "night",
        "reason": "<very short reason or null>",
        "tip": "<very short tip or null>",
        "url": "<URL or null>"
      },
      "stops": [
        {
          "name": "<place name>",
          "addressLine": "<short address line or null>",
          "suburb": "<suburb or null>",
          "city": "<city or null>",
          "state": "<state or null>",
          "postcode": "<postcode or null>",
          "country": "<country or null>",
          "category": "<attraction|museum|restaurant|park|shop|nightlife|family...>",
          "stayMinutes": <int>,
          "timeSlot": "<morning|lunch|afternoon|sunset|evening>",
          "startTime": "<HH:mm>",
          "endTime": "<HH:mm>",
          "mealType": "<for food stops: breakfast|brunch|lunch|dinner|snack|dessert|drinks, otherwise null>",
          "preferredArea": "<short area label or null>",
          "cuisine": "<for food stops: cuisine or dining style, otherwise null>",
          "vibe": "<for food stops: short dining vibe, otherwise null>",
          "budgetLevel": "<for food stops: budget|midrange|premium, otherwise null>",
          "reason": "<very short reason or null>",
          "tip": "<very short tip or null>",
          "url": "<URL or null>"
        }
      ],
      "note": "<very short day summary or null>"
    }
  ]
}

Rules:
1. Return exactly one day in daysPlan, and that dayIndex must be %d.
2. Treat the current day skeleton constraint as hard.
3. Keep the day's non-meal stop count inside the skeleton effective range unless real-world feasibility forces fewer.
4. Every generated day must include one real lunch venue and one real dinner venue.
5. Keep this day geographically compact around one main area and at most one nearby secondary area.
6. Use the adjacent day context only to avoid repeating the same area pattern on every day.
7. Do not output any extra explanation or any second day.
8. Keep copy short and factual. The backend will rewrite copy later.
9. Prefer null over long prose for optional text fields.
10. Keep every string field compact to reduce output length. Do not write long addresses, long descriptions, or full marketing copy.
11. For non-meal stops, preferredArea may be null if the area is already obvious from suburb or city.
12. Before finalizing the day, verify there is exactly one lunch venue and one dinner venue, tight adjacent timing, and no remote out-and-back district jump.
13. The generated day must not repeat a non-meal POI that already appears in the adjacent day context or in the current day skeleton hints.
14. The generated day must not repeat any non-meal POI listed in the already used non-meal POIs block. Replace it with a different real POI in the same area and of a similar visit type.
15. If uncertain, keep the day conservative, compact, and high-confidence rather than trying to maximize variety.
        """.formatted(
                req.city(), totalDays, dayIndex, budget, adults, kids, style, pace, departureDate,
                compositionPolicy, skeleton, adjacentContext, usedPois, totalDays, dayIndex, dayIndex
        );
    }

    private static String compositionPolicy(CreatePlanReq req) {
        return PlanningContext.from(req).promptPolicy();
    }
}
