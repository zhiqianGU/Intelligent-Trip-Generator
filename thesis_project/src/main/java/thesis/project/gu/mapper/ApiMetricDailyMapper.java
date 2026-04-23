package thesis.project.gu.mapper;

import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.ApiMetricDaily;

import java.time.LocalDate;
import java.util.List;

public interface ApiMetricDailyMapper {
    int upsertDailyMetric(ApiMetricDaily metric);

    List<ApiMetricDaily> selectRecentDays(@Param("startDate") LocalDate startDate);
}
