package thesis.project.gu.planning.quality;

import java.util.List;

public record LocalPlanQualityReport(
        int score,
        int errorCount,
        int warningCount,
        List<Warning> warnings
) {
    public boolean hasErrors() {
        return errorCount > 0;
    }

    public record Warning(
            Severity severity,
            String code,
            Integer dayIndex,
            String message
    ) {}

    public enum Severity {
        ERROR,
        WARNING
    }
}
