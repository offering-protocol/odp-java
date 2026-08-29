package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.ServiceDocument;

class DirectoryClientTest {
    @Test
    void selectsOnlyCanonicalEnvironments() {
        assertEquals(DirectoryEnvironment.PRODUCTION, DirectoryClient.create().environment());
        assertEquals(
                "https://sandbox.inflowpay.ai",
                DirectoryClient.create(DirectoryEnvironment.SANDBOX)
                        .environment()
                        .origin()
                        .toString());
    }

    @Test
    void rejectsInvalidSearchLimitsBeforeTransport() {
        assertThrows(IllegalArgumentException.class, () -> new DirectoryModels.SearchRequest("plants", null, 101));
    }

    @Test
    void filtersUnknownProtocolsFromDirectoryResults() {
        DirectoryModels.SearchPage page = DirectoryClient.decodeSearchPage("""
                {"items":[
                  {"service_origin":"https://plants.example","name":"Plants","description":"Plant store",
                   "language":"en","localizations":["en"],"operations":[
                     {"authentication":"not-required","name":"get-offering"},
                     {"authentication":"not-required","name":"list-offerings"}],
                   "protocols":{"enrollment":[{"name":"future-enrollment"}],
                     "payments":[{"authentication":"not-required","name":"future-payment"},
                       {"authentication":"not-required","name":"mpp"}],
                     "trust":[{"name":"future-trust"},{"name":"tap"}]},
                   "indexed_at":"2026-08-28T00:00:00Z"},
                  {"service_origin":"https://future.example","name":"Future","description":"Future service",
                   "language":"en","localizations":["en"],"operations":[
                     {"authentication":"not-required","name":"get-offering"},
                     {"authentication":"not-required","name":"list-offerings"}],
                   "protocols":{"trust":[{"name":"future-trust"}]},
                   "indexed_at":"2026-08-28T00:00:00Z"}
                ]}
                """);

        ServiceDocument.Protocols protocols = page.items().get(0).protocols();
        assertEquals(List.of(new ServiceDocument.TrustProtocol("tap")), protocols.trust());
        assertEquals(1, protocols.payments().size());
        assertNull(protocols.enrollment());
        assertNull(page.items().get(1).protocols());
    }
}
