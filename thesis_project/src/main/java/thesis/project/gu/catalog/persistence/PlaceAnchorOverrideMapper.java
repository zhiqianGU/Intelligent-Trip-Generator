package thesis.project.gu.catalog.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import thesis.project.gu.catalog.domain.PlaceAnchorOverride;

@Mapper
public interface PlaceAnchorOverrideMapper {
    PlaceAnchorOverride findEnabledMatch(
            @Param("name") String name,
            @Param("city") String city,
            @Param("country") String country
    );
}
