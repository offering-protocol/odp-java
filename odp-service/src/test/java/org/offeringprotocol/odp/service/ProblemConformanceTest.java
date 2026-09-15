package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.offeringprotocol.odp.service.Catalog.OFFERINGS;
import static org.offeringprotocol.odp.service.Catalog.get;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.ProblemDetails;

class ProblemConformanceTest {
    /** ERR-01/02/03/07: every failure is a Problem Details document whose members agree. */
    @Test
    void describesEveryFailureAsProblemDetails() {
        ProblemDetails problem = problem(Catalog.service().handle(get(OFFERINGS + "/absent")));

        assertEquals("https://offeringprotocol.org/problems/not-found", problem.type());
        assertEquals("Not found", problem.title());
        assertEquals(404, problem.status());
        assertEquals("NOT_FOUND", problem.code());
        assertEquals("ODP resource not found", problem.detail());
        assertNull(problem.instance());
    }

    /** ERR-04: title stays within 128 code points and detail within 2048, however long the message. */
    @Test
    void boundsTheStringsAProblemCarries() {
        ProblemDetails problem =
                problem(Catalog.failing(new OdpServiceException(400, "INVALID_REQUEST", "d".repeat(5_000)))
                        .handle(get(OFFERINGS + "/plant-1")));

        assertEquals(2_048, problem.detail().codePointCount(0, problem.detail().length()));
        assertTrue(problem.detail().endsWith("…"));
        assertTrue(problem.title().codePointCount(0, problem.title().length()) <= 128);
    }

    /** ERR-21: a Problem Details response stays within 16,384 bytes even at its widest. */
    @Test
    void keepsAProblemWithinItsByteLimit() {
        OdpHttpResponse response = Catalog.failing(
                        new OdpServiceException(400, "I" + "X".repeat(63), "🪴".repeat(5_000)))
                .handle(get(OFFERINGS + "/plant-1"));
        String detail = problem(response).detail();

        assertTrue(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16_384);
        assertEquals(2_048, detail.codePointCount(0, detail.length()));
    }

    /** ERR-06: a code ODP could not carry is replaced by one that matches the status. */
    @ParameterizedTest
    @ValueSource(strings = {"not a code!", "lowercase", "9LEADING", "", "WITH-HYPHEN", "TRAILING "})
    void replacesACodeItCouldNotCarry(String code) {
        assertEquals(
                "INVALID_REQUEST",
                problem(Catalog.failing(new OdpServiceException(400, code, "nope"))
                                .handle(get(OFFERINGS + "/plant-1")))
                        .code(),
                code);
        assertEquals(
                "INTERNAL_ERROR",
                problem(Catalog.failing(new OdpServiceException(503, code, "nope"))
                                .handle(get(OFFERINGS + "/plant-1")))
                        .code(),
                code);
    }

    @Test
    void keepsACodeItCanCarry() {
        assertEquals(
                "RATE_LIMITED9_X",
                problem(Catalog.failing(new OdpServiceException(400, "RATE_LIMITED9_X", "nope"))
                                .handle(get(OFFERINGS + "/plant-1")))
                        .code());
    }

    /** A status outside the range a problem can describe is reported as the failure it really is. */
    @Test
    void reportsAStatusThatIsNotAFailureAsOne() {
        assertEquals(
                500,
                Catalog.failing(new OdpServiceException(200, "NOT_FOUND", "nope"))
                        .handle(get(OFFERINGS + "/plant-1"))
                        .status());
        assertEquals(
                500,
                Catalog.failing(new OdpServiceException(700, "NOT_FOUND", "nope"))
                        .handle(get(OFFERINGS + "/plant-1"))
                        .status());
    }

    /** ERR-32/33: a caller told to come back later is told when. */
    @Test
    void saysWhenToComeBack() {
        assertEquals(
                "60",
                Catalog.failing(new OdpServiceException(429, "RATE_LIMITED", "slow down"))
                        .handle(get(OFFERINGS + "/plant-1"))
                        .headers()
                        .get("Retry-After"));
        assertEquals(
                "5",
                Catalog.failing(OdpServiceException.retryable(503, "UNAVAILABLE", "restarting", 5))
                        .handle(get(OFFERINGS + "/plant-1"))
                        .headers()
                        .get("Retry-After"));
        assertEquals(
                "60",
                Catalog.failing(new OdpServiceException(503, "UNAVAILABLE", "restarting"))
                        .handle(get(OFFERINGS + "/plant-1"))
                        .headers()
                        .get("Retry-After"));
        assertNull(
                Catalog.service().handle(get(OFFERINGS + "/absent")).headers().get("Retry-After"));
    }

    /**
     * A failure this Service did not ask for says nothing about the Service. SEC-33 and PRV-06 turn
     * on that: an exception message can name a query, a table, or a host an Agent may not learn.
     */
    @Test
    void publishesNothingOfAFailureItDidNotAskFor() {
        for (RuntimeException thrown : List.of(
                new IllegalArgumentException("SELECT * FROM secrets WHERE tenant = 7"),
                new IllegalStateException("connection pool 10.1.2.3 exhausted"),
                new NullPointerException("catalog.lookup(...) is null"))) {
            OdpHttpResponse response = Catalog.failing(thrown).handle(get(OFFERINGS + "/plant-1"));

            assertEquals(500, response.status());
            assertEquals("ODP request could not be processed", problem(response).detail());
            assertFalse(response.body().contains("secrets"));
            assertFalse(response.body().contains("10.1.2.3"));
            assertFalse(response.body().contains("catalog.lookup"));
        }
    }

    /** A failure a handler did choose to publish reaches the caller as the handler wrote it. */
    @Test
    void publishesTheFailureAHandlerChoseTo() {
        OdpHttpResponse response = Catalog.failing(
                        new OdpServiceException(400, "INVALID_REQUEST", "filters.price must be a decimal string"))
                .handle(get(OFFERINGS + "/plant-1"));

        assertEquals(400, response.status());
        assertEquals("filters.price must be a decimal string", problem(response).detail());
    }

    /** A cause is kept for the Service's own logs and never serialized into the response. */
    @Test
    void keepsTheCauseOutOfTheResponse() {
        OdpServiceException thrown = new OdpServiceException(
                400, "INVALID_REQUEST", "cursor is unreadable", new IllegalStateException("row 9 of shard 3"));
        OdpHttpResponse response = Catalog.failing(thrown).handle(get(OFFERINGS + "/plant-1"));

        assertEquals("row 9 of shard 3", thrown.getCause().getMessage());
        assertFalse(response.body().contains("shard"));
    }

    @Test
    void carriesTheAllowFieldOnlyOnAMethodRefusal() {
        OdpServiceException refusal = new OdpServiceException(404, "NOT_FOUND", "nope");

        assertNull(refusal.allow());
        assertNull(refusal.retryAfter());
        assertEquals(404, refusal.status());
        assertEquals("NOT_FOUND", refusal.code());
    }

    private static ProblemDetails problem(OdpHttpResponse response) {
        assertEquals("application/problem+json", response.headers().get("Content-Type"));
        return OdpJson.read(response.body(), ProblemDetails.class);
    }
}
