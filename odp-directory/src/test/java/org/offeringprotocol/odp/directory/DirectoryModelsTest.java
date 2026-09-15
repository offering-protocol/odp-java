package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.PaymentOption;
import org.offeringprotocol.odp.core.ServiceDocument;

class DirectoryModelsTest {
    /** An omitted list reads as an empty one, so a caller never has to check for null. */
    @Test
    void readsAnOmittedListAsAnEmptyOne() {
        DirectoryModels.ServiceFilters filters = new DirectoryModels.ServiceFilters(null, null, null, null);
        assertTrue(filters.enrollment().isEmpty());
        assertTrue(filters.keywords().isEmpty());
        assertTrue(filters.operations().isEmpty());
        assertTrue(filters.payments().isEmpty());

        assertTrue(
                new DirectoryModels.PaymentFilter(null, "mpp", null).options().isEmpty());
        assertTrue(new DirectoryModels.Suggestions(null).items().isEmpty());
        assertTrue(new DirectoryModels.Facets(null, null, null, null, null)
                .keywords()
                .isEmpty());

        DirectoryModels.Service service = new DirectoryModels.Service(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(service.localizations().isEmpty());
        assertTrue(service.keywords().isEmpty());
        assertTrue(service.operations().isEmpty());
        assertTrue(service.additional().isEmpty());

        DirectoryModels.SearchPage page = new DirectoryModels.SearchPage(null, null, null, null, null);
        assertTrue(page.items().isEmpty());
        assertTrue(page.issues().isEmpty());
        assertTrue(page.additional().isEmpty());
    }

    /** A caller cannot reach into a record it handed over, nor into one it was handed back. */
    @Test
    void copiesEveryListItIsGiven() {
        List<String> keywords = new ArrayList<>(List.of("plants"));
        DirectoryModels.ServiceFilters filters =
                new DirectoryModels.ServiceFilters(List.of(), keywords, List.of(), List.of());
        keywords.add("seeds");
        assertEquals(List.of("plants"), filters.keywords());
        assertThrows(
                UnsupportedOperationException.class, () -> filters.keywords().add("pots"));

        List<PaymentOption> options = new ArrayList<>(List.of(PaymentOption.BASE));
        DirectoryModels.PaymentFilter payment =
                new DirectoryModels.PaymentFilter(AuthenticationRequirement.NOT_REQUIRED, "mpp", options);
        options.clear();
        assertEquals(List.of(PaymentOption.BASE), payment.options());

        List<String> items = new ArrayList<>(List.of("plants"));
        DirectoryModels.Suggestions suggestions = new DirectoryModels.Suggestions(items);
        items.clear();
        assertEquals(List.of("plants"), suggestions.items());
    }

    @Test
    void carriesTheFiltersASearchWasAskedFor() {
        DirectoryModels.ServiceFilters filters = new DirectoryModels.ServiceFilters(
                List.of(new ServiceDocument.EnrollmentProtocol("api-key")),
                List.of("plants"),
                List.of(new DirectoryModels.OperationFilter(
                        AuthenticationRequirement.NOT_REQUIRED, OdpOperation.LIST_OFFERINGS)),
                List.of(new DirectoryModels.PaymentFilter(
                        AuthenticationRequirement.REQUIRED, "mpp", List.of(PaymentOption.BASE))));

        Stub stub = Stub.json("{\"items\":[]}");
        stub.client().searchServices(new DirectoryModels.SearchRequest("plants", filters, 10));

        assertEquals(OdpOperation.LIST_OFFERINGS, filters.operations().get(0).name());
        assertEquals("mpp", filters.payments().get(0).name());
        assertEquals("POST", stub.lastRequest().method());
    }

    @Test
    void refusesASearchLimitOutsideItsRange() {
        assertThrows(IllegalArgumentException.class, () -> new DirectoryModels.SearchRequest("plants", null, 0));
        assertThrows(IllegalArgumentException.class, () -> new DirectoryModels.SearchRequest("plants", null, 101));
        assertEquals(100, new DirectoryModels.SearchRequest("plants", null, 100).limit());
    }

    @Test
    void namesTheValueAFacetCounted() {
        DirectoryModels.Facet<DirectoryModels.PaymentOptionFacetValue> facet =
                new DirectoryModels.Facet<>(new DirectoryModels.PaymentOptionFacetValue("mpp", PaymentOption.BASE), 3);
        assertEquals("mpp", facet.value().name());
        assertEquals(3, facet.count());
    }
}
