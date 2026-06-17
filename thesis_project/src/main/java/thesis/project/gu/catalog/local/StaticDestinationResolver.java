package thesis.project.gu.catalog.local;

import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.application.DestinationResolver;
import thesis.project.gu.catalog.domain.Destination;

import java.util.Locale;

@Service
public class StaticDestinationResolver implements DestinationResolver {
    @Override
    public Destination resolve(String destinationCandidate) {
        String normalized = normalize(destinationCandidate);
        if (normalized.isBlank()) {
            return Destination.unresolved("");
        }
        if (isBrisbane(normalized)) {
            return new Destination(
                    "AU-QLD-BRISBANE",
                    "Brisbane",
                    "Queensland",
                    "Australia",
                    "Australia/Brisbane",
                    true
            );
        }
        if ("testville".equals(normalized)) {
            return new Destination(
                    "TEST-TESTVILLE",
                    "Testville",
                    "TS",
                    "Testland",
                    "UTC",
                    true
            );
        }
        return Destination.unresolved(displayName(destinationCandidate));
    }

    private boolean isBrisbane(String normalized) {
        return "brisbane".equals(normalized)
                || "bne".equals(normalized)
                || "brisbaneqld".equals(normalized)
                || "brisbanequeensland".equals(normalized)
                || "布里斯班".equals(normalized);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s,_-]+", "")
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private String displayName(String value) {
        return value == null ? "" : value.trim();
    }
}
