package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

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
}
