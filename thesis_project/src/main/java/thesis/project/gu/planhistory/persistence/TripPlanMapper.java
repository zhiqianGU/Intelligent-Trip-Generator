package thesis.project.gu.planhistory.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import thesis.project.gu.planhistory.domain.PlaceDetail;
import thesis.project.gu.planhistory.domain.TripDay;
import thesis.project.gu.planhistory.domain.TripDayStop;
import thesis.project.gu.planhistory.domain.TripDayView;
import thesis.project.gu.planhistory.domain.TripPlan;
import thesis.project.gu.planhistory.domain.TripPlanSummary;
import thesis.project.gu.planhistory.domain.TripStopView;
import thesis.project.gu.planhistory.application.PlanService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Mapper
public interface TripPlanMapper {
    void insertPlan(TripPlan row);             // 回写自增 id
    void insertPlanStyle(@Param("planId") long planId, @Param("styleId") int styleId);

    void insertDay(TripDay row);               // 回写自增 id
    void updateDayHotel(@Param("dayId") long dayId, @Param("hotelPlaceId") Long hotelPlaceId);

    void insertDayStop(TripDayStop row);       // 回写自增 id

    // 查询 style_tag.id by code
    Integer selectStyleIdByCode(@Param("code") String code);

    int updateFavorite(long planId, long userId, boolean favorite);

    List<TripPlanSummary> listByUser(@Param("userId") long userId,
                                     @Param("favorite") Boolean favorite, // null=全部；true=仅收藏
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    int countByUser(@Param("userId") long userId,
                    @Param("favorite") Boolean favorite);

    // 详情：取一个计划 + 天与经停点
    TripPlan findPlanById(@Param("planId") long planId, @Param("userId") long userId);
    List<TripDayView> findDaysByPlan(@Param("planId") long planId);
    List<TripStopView> findStopsByDayIds(@Param("dayIds") List<Long> dayIds);

    int updateTitle(long planId, long userId, String title);

    int updateDayCopy(@Param("planId") long planId,
                      @Param("userId") long userId,
                      @Param("dayIndex") int dayIndex,
                      @Param("note") String note,
                      @Param("hotelReason") String hotelReason,
                      @Param("hotelTip") String hotelTip);

    int updateStopCopy(@Param("planId") long planId,
                       @Param("userId") long userId,
                       @Param("dayIndex") int dayIndex,
                       @Param("seq") int seq,
                       @Param("reason") String reason,
                       @Param("tip") String tip);

    int deletePlan(@Param("planId") long planId, @Param("userId") long userId);

    List<PlaceDetail> findPlacesByIds(@Param("placeIds") List<Long> placeIds);
}
