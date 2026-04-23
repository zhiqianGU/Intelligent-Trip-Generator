package thesis.project.gu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.PlaceAnchorOverride;

@Mapper
public interface PlaceAnchorOverrideMapper {
    PlaceAnchorOverride findEnabledMatch(
            @Param("name") String name,
            @Param("city") String city,
            @Param("country") String country
    );
}
