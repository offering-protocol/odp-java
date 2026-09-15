package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.offeringprotocol.odp.service.Catalog.OFFERINGS;
import static org.offeringprotocol.odp.service.Catalog.get;
import static org.offeringprotocol.odp.service.Catalog.with;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.offeringprotocol.odp.core.Odp;

class LanguageConformanceTest {
    private final OdpService service = Catalog.multilingual("en", "en-GB", "fr", "de-CH-1901");

    /** SVC-60: a localized response says which language it is in and what it varies by. */
    @Test
    void saysWhichLanguageItAnsweredIn() {
        OdpHttpResponse response = service.handle(get(Odp.SERVICE_DOCUMENT_PATH));

        assertEquals("en", response.headers().get("Content-Language"));
        assertEquals("Accept-Language", response.headers().get("Vary"));
    }

    /**
     * SVC-58: RFC 4647 Lookup, so a range is shortened at each subtag until a tag matches. Lookup
     * never crosses to a sibling, which is why de-CH-1902 falls back rather than finding de-CH-1901.
     */
    @ParameterizedTest
    @CsvSource({
        "fr,fr",
        "FR,fr",
        "fr-CA,fr",
        "fr-CA-x-private,fr",
        "en-GB,en-GB",
        "en-GB-oxendict,en-GB",
        "en-US,en",
        "de-CH-1901,de-CH-1901",
        "de-CH-1902,en",
        "de-CH,en",
        "*,en",
        "zz,en",
        "'',en"
    })
    void selectsTheLanguageByLookup(String accepted, String expected) {
        assertEquals(
                expected,
                service.handle(with(OFFERINGS, "Accept-Language", accepted))
                        .headers()
                        .get("Content-Language"));
    }

    /** A single-character subtag is not a language of its own, so Lookup steps past it. */
    @Test
    void stepsPastASingletonSubtagWhileTruncating() {
        assertEquals(
                "fr",
                service.handle(with(OFFERINGS, "Accept-Language", "fr-x-private"))
                        .headers()
                        .get("Content-Language"));
    }

    /** The highest quality a request states is the one that is tried first. */
    @Test
    void triesTheRangesInTheOrderTheRequestRankedThem() {
        assertEquals(
                "fr",
                service.handle(with(OFFERINGS, "Accept-Language", "de;q=0.2, fr;q=0.9, en;q=0.5"))
                        .headers()
                        .get("Content-Language"));
        assertEquals(
                "en",
                service.handle(with(OFFERINGS, "Accept-Language", "fr;q=0, en"))
                        .headers()
                        .get("Content-Language"),
                "a range scored zero is a range the request ruled out");
    }

    /** SVC-59: a request whose ranges match nothing gets the default, never a refusal. */
    @Test
    void answersAnUnmatchedRequestWithTheDefaultRepresentation() {
        OdpHttpResponse response = service.handle(with(OFFERINGS, "Accept-Language", "ja, ko;q=0.8"));

        assertEquals(200, response.status());
        assertEquals("en", response.headers().get("Content-Language"));
    }

    /** The handler is told which language was chosen, not left to negotiate the header again. */
    @Test
    void handsTheSelectedLanguageToTheHandler() {
        OdpService echoing = new OdpService(
                Catalog.template(List.of("en", "fr")),
                Map.of(
                        org.offeringprotocol.odp.core.OdpOperation.LIST_OFFERINGS,
                        new OdpService.Endpoint(
                                org.offeringprotocol.odp.core.AuthenticationRequirement.NOT_REQUIRED,
                                request -> Catalog.page(List.of(Catalog.item("plant-1", request.language())), null)),
                        org.offeringprotocol.odp.core.OdpOperation.GET_OFFERING,
                        new OdpService.Endpoint(
                                org.offeringprotocol.odp.core.AuthenticationRequirement.NOT_REQUIRED,
                                request -> Catalog.offering("plant-1", request.language()))));

        assertEquals(
                "\"name\":\"fr\"",
                extract(echoing.handle(with(OFFERINGS, "Accept-Language", "fr-CA"))
                        .body()));
        assertEquals("\"name\":\"en\"", extract(echoing.handle(get(OFFERINGS)).body()));
    }

    /** A header repeated across several lines is one field, as RFC 9110 5.2 says. */
    @Test
    void readsAFieldSplitAcrossSeveralLines() {
        OdpHttpRequest request =
                new OdpHttpRequest("GET", OFFERINGS, Map.of(), Map.of("Accept-Language", List.of("ja", "fr")), null);

        assertEquals("fr", service.handle(request).headers().get("Content-Language"));
    }

    private static String extract(String body) {
        int at = body.indexOf("\"name\"");
        return body.substring(at, body.indexOf('"', body.indexOf(':', at) + 2) + 1);
    }
}
