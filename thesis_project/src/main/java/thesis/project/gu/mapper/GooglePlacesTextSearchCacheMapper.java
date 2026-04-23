package thesis.project.gu.mapper;

import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.GooglePlacesTextSearchCache;

public interface GooglePlacesTextSearchCacheMapper {
    GooglePlacesTextSearchCache selectByKey(@Param("cacheKey") String cacheKey);

    int upsert(GooglePlacesTextSearchCache cache);
}
