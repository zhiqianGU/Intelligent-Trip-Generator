package thesis.project.gu.catalog.domain;

import java.util.List;

public record CoverageResult(
        boolean generatable,
        boolean preferredCoverageMet,
        List<CoverageGap> gaps,
        List<CoverageGap> softGaps
) {
    public static CoverageResult sufficient() {
        return new CoverageResult(true, true, List.of(), List.of());
    }
}
