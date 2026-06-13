package thesis.project.gu.infrastructure.external.google.cache;

import org.apache.ibatis.annotations.Param;
import thesis.project.gu.infrastructure.external.google.cache.GooglePlacesTextSearchCache;

public interface GooglePlacesTextSearchCacheMapper {
    GooglePlacesTextSearchCache selectByKey(@Param("cacheKey") String cacheKey);

    int upsert(GooglePlacesTextSearchCache cache);
}
