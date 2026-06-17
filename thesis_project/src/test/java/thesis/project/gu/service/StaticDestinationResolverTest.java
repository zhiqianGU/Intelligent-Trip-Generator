package thesis.project.gu.service;

import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.domain.Destination;
import thesis.project.gu.catalog.local.StaticDestinationResolver;

import static org.assertj.core.api.Assertions.assertThat;

class StaticDestinationResolverTest {
    private final StaticDestinationResolver resolver = new StaticDestinationResolver();

    @Test
    void resolvesBrisbaneAliasesToStableDestinationId() {
        Destination english = resolver.resolve("Brisbane");
        Destination chinese = resolver.resolve("布里斯班");

        assertThat(english.destinationId()).isEqualTo("AU-QLD-BRISBANE");
        assertThat(english.city()).isEqualTo("Brisbane");
        assertThat(english.state()).isEqualTo("Queensland");
        assertThat(english.country()).isEqualTo("Australia");
        assertThat(english.timezone()).isEqualTo("Australia/Brisbane");
        assertThat(english.resolved()).isTrue();
        assertThat(chinese).isEqualTo(english);
    }

    @Test
    void resolvesTestvilleFixtureDestination() {
        Destination destination = resolver.resolve("Testville");

        assertThat(destination.destinationId()).isEqualTo("TEST-TESTVILLE");
        assertThat(destination.city()).isEqualTo("Testville");
        assertThat(destination.resolved()).isTrue();
    }

    @Test
    void unsupportedCityKeepsDisplayNameButRemainsUnresolved() {
        Destination destination = resolver.resolve("Sydney");

        assertThat(destination.destinationId()).isNull();
        assertThat(destination.city()).isEqualTo("Sydney");
        assertThat(destination.resolved()).isFalse();
    }
}
