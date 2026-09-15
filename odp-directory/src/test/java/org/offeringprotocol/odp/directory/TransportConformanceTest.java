package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransportConformanceTest {
    private static final String EMPTY_PAGE = "{\"items\":[]}";
    private static final String JSON = "application/json";

    @Test
    void searchesWithAJsonRequestOnTheCanonicalOrigin() {
        Stub stub = Stub.json(EMPTY_PAGE);
        stub.client().searchServices(new DirectoryModels.SearchRequest("plants", null, 10));

        assertEquals(
                "https://sandbox.inflowpay.ai/v1/services/search",
                stub.lastRequest().uri().toString());
        assertEquals("POST", stub.lastRequest().method());
        assertEquals(JSON, stub.lastRequest().headers().firstValue("Accept").orElseThrow());
        assertEquals(
                JSON, stub.lastRequest().headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void asksForSuggestionsWithAnEscapedPrefix() {
        Stub stub = Stub.json(EMPTY_PAGE);
        stub.client().suggestServices("pl ants&", 5);

        assertEquals(
                "https://sandbox.inflowpay.ai/v1/services/suggestions?prefix=pl+ants%26&limit=5",
                stub.lastRequest().uri().toString());
        assertEquals("GET", stub.lastRequest().method());
    }

    @Test
    void leavesTheLimitOutWhenTheCallerDidNotSetOne() {
        Stub stub = Stub.json(EMPTY_PAGE);
        stub.client().suggestServices("pl", null);

        assertEquals(
                "https://sandbox.inflowpay.ai/v1/services/suggestions?prefix=pl",
                stub.lastRequest().uri().toString());
    }

    @Test
    void refusesASuggestionRequestItCannotSend() {
        DirectoryClient client = Stub.json(EMPTY_PAGE).client();
        assertThrows(IllegalArgumentException.class, () -> client.suggestServices(null, null));
        assertThrows(IllegalArgumentException.class, () -> client.suggestServices("  ", null));
        assertThrows(IllegalArgumentException.class, () -> client.suggestServices("a".repeat(129), null));
        assertThrows(IllegalArgumentException.class, () -> client.suggestServices("pl", 0));
        assertThrows(IllegalArgumentException.class, () -> client.suggestServices("pl", 26));
        assertThrows(NullPointerException.class, () -> client.searchServices(null));
    }

    /** RFC 9110 15.4: a 303, and a 301 or 302 answering a POST, continue as a GET with no body. */
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303})
    void continuesARedirectedPostAsAGet(int status) {
        Stub stub = Stub.script(Stub.redirect(status, "/v1/services/search?page=2"), Stub.reply(200, EMPTY_PAGE, JSON));
        stub.client().searchServices(new DirectoryModels.SearchRequest("plants", null, null));

        assertEquals(2, stub.requests().size());
        assertEquals("GET", stub.lastRequest().method());
        assertEquals(
                "https://sandbox.inflowpay.ai/v1/services/search?page=2",
                stub.lastRequest().uri().toString());
        assertTrue(stub.lastRequest().headers().firstValue("Content-Type").isEmpty());
    }

    /** A 307 or 308 keeps the method and the body it was answering. */
    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void keepsTheMethodAcrossAPreservingRedirect(int status) {
        Stub stub = Stub.script(Stub.redirect(status, "/v1/services/search/v2"), Stub.reply(200, EMPTY_PAGE, JSON));
        stub.client().searchServices(new DirectoryModels.SearchRequest("plants", null, null));

        assertEquals("POST", stub.lastRequest().method());
        assertEquals(
                JSON, stub.lastRequest().headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void stopsAtItsRedirectLimit() {
        Stub stub = Stub.script(Stub.redirect(302, "/v1/services/suggestions?prefix=pl"));
        DirectoryClient client = stub.client();

        assertEquals(
                "Directory response exceeded its redirect limit",
                assertThrows(IllegalStateException.class, () -> client.suggestServices("pl", null))
                        .getMessage());
        assertEquals(6, stub.requests().size());
    }

    @Test
    void refusesARedirectThatNamesNowhere() {
        DirectoryClient client = Stub.script(Stub.raw(302, "", Map.of())).client();
        assertEquals(
                "Directory redirect omitted Location",
                assertThrows(IllegalStateException.class, () -> client.suggestServices("pl", null))
                        .getMessage());
    }

    /** A directory may move a request within its own origin and nowhere else. */
    @Test
    void refusesARedirectOffTheCanonicalOrigin() {
        DirectoryClient client =
                Stub.script(Stub.redirect(302, "https://elsewhere.example/v1")).client();
        assertEquals(
                "Directory continuation must remain on the canonical origin",
                assertThrows(IllegalArgumentException.class, () -> client.suggestServices("pl", null))
                        .getMessage());
    }

    @Test
    void readsAContinuationOnTheCanonicalOrigin() {
        Stub stub = Stub.json(EMPTY_PAGE);
        stub.client().continueSearchServices("/v1/services/search?cursor=c2");

        assertEquals(
                "https://sandbox.inflowpay.ai/v1/services/search?cursor=c2",
                stub.lastRequest().uri().toString());
        assertEquals("GET", stub.lastRequest().method());
    }

    /** A written-out default port is the same origin, so a continuation naming one still belongs. */
    @Test
    void readsAContinuationThatWritesOutTheDefaultPort() {
        Stub stub = Stub.json(EMPTY_PAGE);
        DirectoryModels.SearchPage page =
                stub.client().continueSearchServices("https://sandbox.inflowpay.ai:443/v1/services/search?cursor=c");
        assertTrue(page.items().isEmpty());
    }

    @Test
    void refusesAContinuationItCannotFollow() {
        DirectoryClient client = Stub.json(EMPTY_PAGE).client();
        assertThrows(IllegalArgumentException.class, () -> client.continueSearchServices(null));
        assertThrows(IllegalArgumentException.class, () -> client.continueSearchServices(" "));
        assertThrows(IllegalArgumentException.class, () -> client.continueSearchServices("/v1?c=" + "a".repeat(2048)));
        assertThrows(IllegalArgumentException.class, () -> client.continueSearchServices("/v1?c=a\nb"));
        assertThrows(IllegalArgumentException.class, () -> client.continueSearchServices("/v1?c=café"));
        assertThrows(
                IllegalArgumentException.class, () -> client.continueSearchServices("https://elsewhere.example/v1"));
    }

    /** ERR-20: a body past the limit is refused rather than read into memory and parsed. */
    @Test
    void refusesABodyPastItsLimit() {
        DirectoryClient large = Stub.json("{\"items\":[], \"padding\":\"" + "a".repeat(524_288) + "\"}")
                .client();
        assertEquals(
                "Directory response exceeds its byte limit",
                assertThrows(IllegalStateException.class, () -> large.suggestServices("pl", null))
                        .getMessage());

        DirectoryClient failing =
                Stub.always(500, "e".repeat(16_385), "application/problem+json").client();
        assertEquals(
                "Directory response exceeds its byte limit",
                assertThrows(IllegalStateException.class, () -> failing.suggestServices("pl", null))
                        .getMessage());
    }

    /** A declared length past the limit is refused before the body is decoded at all. */
    @Test
    void refusesADeclaredLengthPastItsLimit() {
        DirectoryClient client = Stub.declaring(EMPTY_PAGE, JSON, 10_000_000).client();
        assertEquals(
                "Directory response exceeds its byte limit",
                assertThrows(IllegalStateException.class, () -> client.suggestServices("pl", null))
                        .getMessage());
    }

    @Test
    void acceptsADeclaredLengthWithinTheLimit() {
        DirectoryClient client =
                Stub.declaring(EMPTY_PAGE, JSON, EMPTY_PAGE.length()).client();
        assertTrue(client.suggestServices("pl", null).isEmpty());
    }

    /** MED-08/09: the media type is compared by essence, case-insensitively, and must match exactly. */
    @Test
    void comparesTheMediaTypeByItsEssence() {
        assertTrue(Stub.always(200, EMPTY_PAGE, "APPLICATION/JSON; charset=utf-8")
                .client()
                .suggestServices("pl", null)
                .isEmpty());

        for (String type : List.of("application/jsonx", "text/html", "", "application/problem+json")) {
            DirectoryClient client = Stub.always(200, EMPTY_PAGE, type).client();
            assertEquals(
                    "Directory response must use application/json",
                    assertThrows(IllegalStateException.class, () -> client.suggestServices("pl", null))
                            .getMessage());
        }
    }

    /** A failure describes itself; it does not echo back whatever the response happened to carry. */
    @Test
    void describesAFailureWithoutEchoingItsBody() {
        DirectoryRequestException thrown = assertThrows(
                DirectoryRequestException.class,
                () -> Stub.always(503, "<html>upstream failed\nGET /admin HTTP/1.1</html>", "text/html")
                        .client()
                        .suggestServices("pl", null));

        assertEquals("Directory request failed with HTTP 503", thrown.getMessage());
        assertEquals(
                "Directory request failed with HTTP 199",
                assertThrows(
                                DirectoryRequestException.class,
                                () -> Stub.always(199, "", JSON).client().suggestServices("pl", null))
                        .getMessage());
        assertEquals(503, thrown.status());
        assertEquals("text/html", thrown.headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void quotesOnlyAStructuredFieldOfAJsonError() {
        assertEquals(
                "Directory request failed with HTTP 400: query is required",
                failure("{\"detail\":\"query is required\",\"title\":\"Bad Request\"}", "application/problem+json"));
        assertEquals(
                "Directory request failed with HTTP 400: Bad Request",
                failure("{\"title\":\"Bad Request\"}", "application/problem+json"));
        assertEquals(
                "Directory request failed with HTTP 400: rate limited",
                failure("{\"message\":\"rate limited\"}", JSON));
    }

    /** A field that could forge a log line is flattened before it is quoted. */
    @Test
    void flattensTheTextItQuotes() {
        assertEquals(
                "Directory request failed with HTTP 400: forged INFO ok",
                failure("{\"detail\":\"forged\\n\\tINFO   ok\"}", JSON));
        assertEquals(
                "Directory request failed with HTTP 400: " + "a".repeat(2048) + "…",
                failure("{\"detail\":\"" + "a".repeat(4000) + "\"}", JSON));
    }

    @Test
    void quotesNothingFromAnErrorItCannotRead() {
        String summary = "Directory request failed with HTTP 400";
        assertEquals(summary, failure("not json at all", JSON));
        assertEquals(summary, failure("[1,2,3]", JSON));
        assertEquals(summary, failure("null", JSON));
        assertEquals(summary, failure("{\"detail\":7}", JSON));
        assertEquals(summary, failure("{\"detail\":\"   \"}", JSON));
        assertEquals(summary, failure("{}", JSON));
        assertEquals(summary, failure("{\"detail\":\"readable\"}", "text/plain"));
    }

    @Test
    void reportsATransportFailureAsSuchAndKeepsAnInterruptVisible() {
        DirectoryClient broken = Stub.script(request -> {
                    throw new IOException("no route to host");
                })
                .client();
        assertEquals(
                "Directory request failed",
                assertThrows(IllegalStateException.class, () -> broken.suggestServices("pl", null))
                        .getMessage());

        DirectoryClient interrupted = Stub.script(request -> {
                    throw new InterruptedException("stopped");
                })
                .client();
        assertEquals(
                "Directory request was interrupted",
                assertThrows(IllegalStateException.class, () -> interrupted.suggestServices("pl", null))
                        .getMessage());
        assertTrue(Thread.interrupted(), "the interrupt is handed back to the caller's thread");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static String failure(String body, String contentType) {
        return assertThrows(
                        DirectoryRequestException.class,
                        () -> Stub.always(400, body, contentType).client().suggestServices("pl", null))
                .getMessage();
    }
}
