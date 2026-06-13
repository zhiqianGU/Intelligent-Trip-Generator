package thesis.project.gu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.local.LocalPoiCatalog;
import thesis.project.gu.catalog.local.LocalPoiItem;
import thesis.project.gu.catalog.local.LocalPoiCatalogService;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPoiCatalogServiceTest {
    private final LocalPoiCatalogService service = new LocalPoiCatalogService(new ObjectMapper());

    @Test
    void loadsBrisbaneLocalCatalogFromResourceFiles() {
        LocalPoiCatalog catalog = service.catalogForCity("Brisbane");

        assertThat(catalog.city()).isEqualTo("Brisbane");
        assertThat(catalog.country()).isEqualTo("Australia");
        assertThat(catalog.state()).isEqualTo("QLD");
        assertThat(catalog.currency()).isEqualTo("AUD");
        assertThat(catalog.hotels()).hasSize(8);
        assertThat(catalog.attractions()).hasSize(90);
        assertThat(catalog.restaurants()).hasSize(72);
        assertThat(catalog.totalItemCount()).isEqualTo(170);
    }

    @Test
    void loadsGenericCityCatalogFromCanonicalResourceFiles() {
        LocalPoiCatalog catalog = service.catalogForCity("Testville");

        assertThat(catalog.city()).isEqualTo("Testville");
        assertThat(catalog.country()).isEqualTo("Testland");
        assertThat(catalog.state()).isEqualTo("TS");
        assertThat(catalog.currency()).isEqualTo("TST");
        assertThat(catalog.hotels()).hasSize(1);
        assertThat(catalog.attractions()).hasSize(2);
        assertThat(catalog.restaurants()).hasSize(2);
        assertThat(catalog.totalItemCount()).isEqualTo(5);
        assertThat(catalog.hotels().getFirst().type()).isEqualTo("hotel");
        assertThat(catalog.attractions().getFirst().type()).isEqualTo("attraction");
        assertThat(catalog.restaurants().getFirst().type()).isEqualTo("restaurant");
    }

    @Test
    void brisbaneCatalogItemsHaveRequiredSchedulingFields() {
        LocalPoiCatalog catalog = service.catalogForCity("brisbane");

        assertThat(catalog.hotels()).allSatisfy(this::hasCoreFields);
        assertThat(catalog.attractions()).allSatisfy(item -> {
            hasCoreFields(item);
            assertThat(item.timeSlots()).isNotEmpty();
            assertThat(item.priority()).isBetween(1, 100);
        });
        assertThat(catalog.restaurants()).allSatisfy(item -> {
            hasCoreFields(item);
            assertThat(item.mealTypes()).isNotEmpty();
            assertThat(item.cuisine()).isNotBlank();
        });
    }

    @Test
    void unsupportedCityReturnsEmptyCatalog() {
        LocalPoiCatalog catalog = service.catalogForCity("Sydney");

        assertThat(catalog.city()).isEqualTo("Sydney");
        assertThat(catalog.totalItemCount()).isZero();
    }

    private void hasCoreFields(LocalPoiItem item) {
        assertThat(item.name()).isNotBlank();
        assertThat(item.category()).isNotBlank();
        assertThat(item.city()).isEqualToIgnoringCase("Brisbane");
        assertThat(item.area()).isNotBlank();
        assertThat(item.addressLine()).isNotBlank();
        assertThat(item.latitude()).isBetween(-28.5, -26.5);
        assertThat(item.longitude()).isBetween(152.0, 154.5);
        assertThat(item.budgetLevel()).isNotBlank();
        assertThat(item.familyFriendly()).isNotNull();
    }
}
