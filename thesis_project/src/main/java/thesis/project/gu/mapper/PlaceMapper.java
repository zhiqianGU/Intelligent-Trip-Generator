package thesis.project.gu.mapper;



import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.Place;

import java.util.List;

public interface PlaceMapper {
    Place findBySourceAndExternalRef(@Param("source") String source,
                                     @Param("externalRef") String externalRef);

    /** UPSERT：如果已存在则更新基础字段，否则插入 */
    int upsert(Place p);

    /** 批量 UPSERT（可选提升写入吞吐） */
    int batchUpsert(@Param("list") List<Place> list);
}
