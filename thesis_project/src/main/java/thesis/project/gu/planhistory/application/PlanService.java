package thesis.project.gu.planhistory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.planhistory.persistence.TripPlanMapper;
import thesis.project.gu.planhistory.domain.TripDayView;
import thesis.project.gu.planhistory.domain.TripPlanSummary;
import thesis.project.gu.planhistory.domain.TripStopView;
import thesis.project.gu.planhistory.domain.PlaceDetail;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlanService {
    private final TripPlanMapper planMapper;

    public PlanService(TripPlanMapper planMapper) {
        this.planMapper = planMapper;
    }

    /** 仅允许计划所有者修改收藏状态 */
    @Transactional
    public void setFavorite(long userId, long planId, boolean favorite) {
        int affected = planMapper.updateFavorite(planId, userId, favorite);
        if (affected == 0) {
            // 要么不存在，要么不是该用户的计划
            throw new NavigatorException(ErrorCode.NOT_FOUND, "Plan not found or no permission");
        }
    }

    public Paged<TripPlanSummary> listMyPlans(long userId, int page, int size, Boolean favorite) {
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * limit;
        var rows = planMapper.listByUser(userId, favorite, offset, limit);
        int total = planMapper.countByUser(userId, favorite);
        return new Paged<>(rows, page, limit, total);
    }

    /** 详情：基本信息 + 天与经停点 */
    public PlanDetail getMyPlanDetail(long userId, long planId) {
        var plan = planMapper.findPlanById(planId, userId);
        if (plan == null) throw ErrorCode.NOT_FOUND.ex("plan not found");

        var days = planMapper.findDaysByPlan(planId);
        var dayIds = days.stream().map(TripDayView::getId).toList();

        var stops = dayIds.isEmpty()
                ? List.<TripStopView>of()
                : planMapper.findStopsByDayIds(dayIds);

        // 收集所有 placeId：hotelPlaceId + stop.placeId
        Set<Long> placeIds = new HashSet<>();
        for (var d : days) {
            if (d.getHotelPlaceId() != null) {
                placeIds.add(d.getHotelPlaceId());
            }
        }
        for (var s : stops) {
            if (s.getPlaceId() != null) {
                placeIds.add(s.getPlaceId());
            }
        }

        Map<Long, PlaceDetail> placeMap = placeIds.isEmpty()
                ? Map.of()
                : planMapper.findPlacesByIds(new ArrayList<>(placeIds))
                .stream()
                .collect(Collectors.toMap(PlaceDetail::id, p -> p));

        // stops 按 dayId 分组
        Map<Long, List<TripStopView>> stopsByDayId = stops.stream()
                .collect(Collectors.groupingBy(TripStopView::getDayId));

        List<DayDetail> daysPlan = days.stream()
                .sorted(Comparator.comparing(TripDayView::getDayIndex))
                .map(day -> {
                    PlaceDetail hotel = day.getHotelPlaceId() == null
                            ? null
                            : placeMap.get(day.getHotelPlaceId());

                    List<StopDetail> stopDetails = stopsByDayId
                            .getOrDefault(day.getId(), List.of())
                            .stream()
                            .sorted(Comparator.comparing(TripStopView::getSeq))
                            .map(stop -> new StopDetail(
                                    stop.getId(),
                                    stop.getDayId(),
                                    stop.getSeq(),
                                    stop.getDwellMinutes(),
                                    stop.getNote(),
                                    stop.getCategory(),
                                    stop.getTimeSlot(),
                                    stop.getStartTime(),
                                    stop.getEndTime(),
                                    stop.getReason(),
                                    stop.getTip(),
                                    stop.getPlaceId() == null ? null : placeMap.get(stop.getPlaceId())
                            ))
                            .toList();

                    return new DayDetail(
                            day.getId(),
                            day.getDayIndex(),
                            hotel,
                            stopDetails,
                            day.getNote(),
                            day.getHotelReason(),
                            day.getHotelTip()
                    );
                })
                .toList();

        return new PlanDetail(plan, daysPlan);
    }

    @Transactional
    public void renamePlan(long userId, long planId, String title) {
        int affected = planMapper.updateTitle(planId, userId, title);
        if (affected == 0) throw ErrorCode.NOT_FOUND.ex("plan not found or no permission");
    }

    @Transactional
    public void updatePlanCopy(long userId, long planId, thesis.project.gu.planning.api.dto.PlanDraftResponse draft) {
        var plan = planMapper.findPlanById(planId, userId);
        if (plan == null) throw ErrorCode.NOT_FOUND.ex("plan not found or no permission");
        if (draft == null || draft.daysPlan() == null) {
            return;
        }

        for (var day : draft.daysPlan()) {
            if (day == null || day.dayIndex() <= 0) {
                continue;
            }
            planMapper.updateDayCopy(
                    planId,
                    userId,
                    day.dayIndex(),
                    day.note(),
                    day.hotel() == null ? null : day.hotel().reason(),
                    day.hotel() == null ? null : day.hotel().tip()
            );
            if (day.stops() == null) {
                continue;
            }
            for (int i = 0; i < day.stops().size(); i++) {
                var stop = day.stops().get(i);
                if (stop == null) {
                    continue;
                }
                planMapper.updateStopCopy(
                        planId,
                        userId,
                        day.dayIndex(),
                        i + 1,
                        stop.reason(),
                        stop.tip()
                );
            }
        }
    }

    // 简单分页封装
    @Transactional
    public void deletePlan(long userId, long planId) {
        int affected = planMapper.deletePlan(planId, userId);
        if (affected == 0) throw ErrorCode.NOT_FOUND.ex("plan not found or no permission");
    }

    public String buildAiPlanCacheKey(thesis.project.gu.planning.api.dto.CreatePlanReq req) {
        if (req == null) return "empty";
        String styles = (req.style() == null) ? "" :
                req.style().stream()
                        .filter(Objects::nonNull)
                        .map(s -> s.trim().toLowerCase())
                        .sorted()
                        .collect(Collectors.joining(","));
        int adults = (req.party() != null && req.party().adults() != null) ? req.party().adults() : 1;
        int kids = (req.party() != null && req.party().kids() != null) ? req.party().kids() : 0;
        String raw = "%s|%d|%s|%d|%d|%s|%s|%s|%s".formatted(
                normalize(req.city()),
                req.days(),
                req.budget() == null ? "" : req.budget(),
                adults,
                kids,
                normalize(req.pace()),
                normalize(req.mainModel()),
                styles,
                normalize(req.departureDate())
        );
        return Integer.toHexString(raw.hashCode());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    public record Paged<T>(List<T> items, int page, int size, int total) {}

    public record PlanDetail(
            thesis.project.gu.planhistory.domain.TripPlan plan,
            List<DayDetail> daysPlan
    ) {}

    public record DayDetail(
            Long id,
            Integer dayIndex,
            PlaceDetail hotel,
            List<StopDetail> stops,
            String note,
            String hotelReason,
            String hotelTip
    ) {}

    public record StopDetail(
            Long id,
            Long dayId,
            Integer seq,
            Integer dwellMinutes,
            String note,
            String category,
            String timeSlot,
            String startTime,
            String endTime,
            String reason,
            String tip,
            PlaceDetail place
    ) {}


}
