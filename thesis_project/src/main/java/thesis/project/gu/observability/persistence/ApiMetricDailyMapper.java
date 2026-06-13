package thesis.project.gu.observability.persistence;

import org.apache.ibatis.annotations.Param;
import thesis.project.gu.observability.domain.ApiMetricDaily;

import java.time.LocalDate;
import java.util.List;

public interface ApiMetricDailyMapper {
    int upsertDailyMetric(ApiMetricDaily metric);

    List<ApiMetricDaily> selectRecentDays(@Param("startDate") LocalDate startDate);
}
