package thesis.project.gu.catalog.application;

import thesis.project.gu.catalog.domain.Destination;

public interface DestinationResolver {
    Destination resolve(String destinationCandidate);
}
