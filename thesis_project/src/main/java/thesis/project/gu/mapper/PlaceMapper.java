package thesis.project.gu.mapper;



import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.Place;

import java.util.List;

public interface PlaceMapper {
    Place findBySourceAndExternalRef(@Param("source") String source,
                                     @Param("externalRef") String externalRef);

    List<Place> findCachedGeocodeMatches(@Param("query") String query,
                                         @Param("city") String city);

    List<Place> findGeocodeBackfillTargets(@Param("query") String query,
                                           @Param("city") String city);

    int updateCoordinatesById(@Param("id") Long id,
                              @Param("latitude") Double latitude,
                              @Param("longitude") Double longitude);

    /** UPSERT：如果已存在则更新基础字段，否则插入 */
    int upsert(Place p);

    /** 批量 UPSERT（可选提升写入吞吐） */
    int batchUpsert(@Param("list") List<Place> list);

    Long findExistingId(Place row);
}
