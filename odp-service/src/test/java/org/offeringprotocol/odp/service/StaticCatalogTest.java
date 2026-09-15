package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.offeringprotocol.odp.service.Catalog.COLLECTIONS;
import static org.offeringprotocol.odp.service.Catalog.OFFERINGS;
import static org.offeringprotocol.odp.service.Catalog.get;
import static org.offeringprotocol.odp.service.Catalog.query;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.offeringprotocol.odp.core.Collection;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.Page;

class StaticCatalogTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    /** A catalog is a snapshot: a list the caller goes on editing is not the catalog it handed over. */
    @Test
    void snapshotsWhatTheCallerHandedOver() {
        List<Offering> offerings = new ArrayList<>(List.of(Catalog.offering("plant-1", "Rubber Plant")));
        List<Collection> collections = new ArrayList<>(List.of(Catalog.collection("plants")));
        OdpService service = new OdpService(
                Catalog.template(List.of("en")), StaticCatalog.create(offerings, collections, KEY.clone()));

        offerings.add(Catalog.offering("plant-2", "Snake Plant"));
        collections.add(Catalog.collection("trees"));

        assertTrue(service.handle(get(OFFERINGS)).body().contains("plant-1"));
        assertFalse(service.handle(get(OFFERINGS)).body().contains("plant-2"));
        assertFalse(service.handle(get(COLLECTIONS)).body().contains("trees"));
    }

    @Test
    void publishesCollectionHandlersOnlyWhenItHasCollections() {
        assertEquals(
                2,
                StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of())
                        .size());
        assertEquals(
                5,
                StaticCatalog.create(
                                List.of(Catalog.offering("plant-1", "Rubber Plant")),
                                List.of(Catalog.collection("plants")))
                        .size());
    }

    @Test
    void refusesACatalogItCannotIndex() {
        Offering duplicate = Catalog.offering("plant-1", "Snake Plant");
        assertEquals(
                "Offering identifiers must be unique",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> StaticCatalog.create(
                                        List.of(Catalog.offering("plant-1", "Rubber Plant"), duplicate), List.of()))
                        .getMessage());
        assertEquals(
                "Collection identifiers must be unique",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> StaticCatalog.create(
                                        List.of(), List.of(Catalog.collection("plants"), Catalog.collection("plants"))))
                        .getMessage());
        assertEquals(
                "Offering identifiers must be present",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> StaticCatalog.create(List.of(Catalog.offering(null, "Nameless")), List.of()))
                        .getMessage());
    }

    /** PAG-23: a cursor a caller could forge is not integrity protected at all. */
    @Test
    void refusesAContinuationKeyTooShortToSignWith() {
        assertEquals(
                "continuationKey must contain at least 32 bytes",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> StaticCatalog.create(List.of(), List.of(), new byte[31]))
                        .getMessage());
        assertThrows(NullPointerException.class, () -> StaticCatalog.create(List.of(), List.of(), null));
        assertThrows(NullPointerException.class, () -> StaticCatalog.create(null, List.of(), KEY.clone()));
    }

    /** A key the caller goes on editing is not the key this catalog signs with. */
    @Test
    void snapshotsTheContinuationKey() {
        byte[] key = KEY.clone();
        OdpService service = new OdpService(
                Catalog.template(List.of("en")),
                StaticCatalog.create(
                        List.of(Catalog.offering("plant-1", "A"), Catalog.offering("plant-2", "B")), List.of(), key));
        java.util.Arrays.fill(key, (byte) 0);

        assertEquals(200, service.handle(get(OFFERINGS, query("limit", "1"))).status());
    }

    // -- pagination -------------------------------------------------------------------------

    /** PAG-02/03: a page that has a successor says so, and the final one does not. */
    @Test
    void walksTheCatalogOnePageAtATime() {
        OdpService service = paged(5);
        List<String> seen = new ArrayList<>();
        String path = OFFERINGS;
        Map<String, List<String>> parameters = query("limit", "2");
        for (int page = 0; page < 5; page++) {
            Page<Offering> decoded =
                    OdpJson.parsePage(service.handle(get(path, parameters)).body(), Offering.class);
            decoded.items().forEach(item -> seen.add(item.id()));
            if (decoded.next() == null) {
                break;
            }
            path = decoded.next().substring(0, decoded.next().indexOf('?'));
            parameters = parse(decoded.next());
        }

        assertEquals(List.of("plant-0", "plant-1", "plant-2", "plant-3", "plant-4"), seen);
    }

    @Test
    void omitsTheContinuationFromTheFinalPage() {
        OdpService service = paged(2);

        assertFalse(service.handle(get(OFFERINGS)).body().contains("\"next\""));
        assertTrue(service.handle(get(OFFERINGS, query("limit", "1"))).body().contains("\"next\""));
    }

    /** PAG-12: the continuation preserves every input needed to carry on the same operation. */
    @Test
    void carriesTheRequestForwardInTheContinuation() {
        String next = OdpJson.parsePage(
                        paged(3).handle(get(
                                        OFFERINGS, Map.of("limit", List.of("1"), "representation", List.of("full"))))
                                .body(),
                        Offering.class)
                .next();

        assertTrue(next.startsWith(OFFERINGS + "?cursor="));
        assertTrue(next.contains("&representation=full"));
        assertTrue(next.contains("&limit=1"));
    }

    /** PAG-14: a Service chooses its own page size when the request does not. */
    @Test
    void choosesItsOwnPageSizeWhenTheRequestDoesNot() {
        Page<Offering> page = OdpJson.parsePage(paged(60).handle(get(OFFERINGS)).body(), Offering.class);

        assertEquals(50, page.items().size());
    }

    @Test
    void listsOnlyTheOfferingsOfTheCollectionThatWasAsked() {
        Offering member = withCollections("plant-1", List.of("plants"));
        Offering outsider = withCollections("plant-2", List.of("trees"));
        OdpService service = new OdpService(
                Catalog.template(List.of("en")),
                StaticCatalog.create(
                        List.of(member, outsider),
                        List.of(Catalog.collection("plants"), Catalog.collection("trees")),
                        KEY.clone()));

        String body = service.handle(get(COLLECTIONS + "/plants/offerings")).body();
        assertTrue(body.contains("plant-1"));
        assertFalse(body.contains("plant-2"));
        assertEquals(404, service.handle(get(COLLECTIONS + "/absent/offerings")).status());
    }

    // -- cursors ----------------------------------------------------------------------------

    /** PAG-26: a cursor is untrusted input, and possession of one authorizes nothing. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "notacursor",
                "one.two.three",
                "!!!.!!!",
                "aGVsbG8.aGVsbG8",
            })
    void refusesACursorItDidNotIssue(String cursor) {
        OdpHttpResponse response = paged(5).handle(get(OFFERINGS, Map.of("cursor", List.of(cursor))));

        assertEquals(410, response.status(), cursor);
        assertTrue(response.body().contains("CONTINUATION_EXPIRED"));
    }

    @Test
    void refusesACursorWhoseSignatureDoesNotCover() {
        String payload = payload(System.currentTimeMillis() / 1000 + 3_600, 1, 50, "terse", OFFERINGS);
        String forged = payload + "."
                + encode(sign(payload, "another key that is also long enough!!".getBytes(StandardCharsets.UTF_8)));

        assertEquals(
                410,
                paged(5).handle(get(OFFERINGS, Map.of("cursor", List.of(forged))))
                        .status());
    }

    /** PAG-20: an expired continuation says so rather than silently restarting the traversal. */
    @Test
    void refusesAnExpiredCursorRatherThanRestarting() {
        String expired = signed(payload(System.currentTimeMillis() / 1000 - 1, 1, 50, "terse", OFFERINGS));
        OdpHttpResponse response = paged(5).handle(get(OFFERINGS, Map.of("cursor", List.of(expired))));

        assertEquals(410, response.status());
        assertFalse(response.body().contains("plant-0"));
    }

    /** A cursor is bound to the exact request it was issued for, not just to this Service. */
    @Test
    void refusesACursorIssuedForAnotherRequest() {
        long future = System.currentTimeMillis() / 1000 + 7_200;
        OdpService service = paged(5);

        for (String cursor : List.of(
                signed(payload(future, 1, 99, "terse", OFFERINGS)),
                signed(payload(future, 1, 50, "full", OFFERINGS)),
                signed(payload(future, 1, 50, "terse", COLLECTIONS)),
                signed(payload(future, -1, 50, "terse", OFFERINGS)),
                signed(payload(future, 1, 50, "terse", OFFERINGS) + "\nextra"),
                signed("not\nfive\nnewline\nseparated"),
                signed(payload(future, 1, 50, "terse", OFFERINGS).replace("\n1\n", "\nmany\n")))) {
            assertEquals(
                    410,
                    service.handle(get(OFFERINGS, Map.of("cursor", List.of(cursor))))
                            .status(),
                    cursor);
        }
        assertEquals(
                200,
                service.handle(get(
                                OFFERINGS,
                                Map.of("cursor", List.of(signed(payload(future, 1, 50, "terse", OFFERINGS))))))
                        .status());
    }

    /** PAG-19: the lifetime is quantised, so a cursor never reveals the moment it was issued. */
    @Test
    void issuesTheSameLifetimeToEveryCursorOfAnHour() {
        OdpService service = paged(5);
        String first = cursorOf(service);
        String second = cursorOf(service);

        assertEquals(expiryOf(first), expiryOf(second));
        assertTrue(expiryOf(first) - System.currentTimeMillis() / 1000 >= 3_600, "at least the hour PAG-19 requires");
        assertTrue(expiryOf(first) - System.currentTimeMillis() / 1000 <= 7_200);
        assertEquals(0, expiryOf(first) % 3_600, "an hour boundary, not a moment of issuance");
    }

    /** Two Services signing with different keys do not accept each other's continuations. */
    @Test
    void keepsCursorsToTheServiceThatIssuedThem() {
        String cursor = cursorOf(paged(5));
        OdpService other = new OdpService(
                Catalog.template(List.of("en")),
                StaticCatalog.create(
                        offerings(5), List.of(), "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz".getBytes(StandardCharsets.UTF_8)));

        assertEquals(
                410,
                other.handle(get(OFFERINGS, Map.of("cursor", List.of(cursor), "limit", List.of("1"))))
                        .status());
    }

    @Test
    void issuesADifferentCursorForEachPosition() {
        OdpService service = paged(5);
        Page<Offering> first = OdpJson.parsePage(
                service.handle(get(OFFERINGS, query("limit", "1"))).body(), Offering.class);
        Page<Offering> second = OdpJson.parsePage(
                service.handle(get(first.next().substring(0, first.next().indexOf('?')), parse(first.next())))
                        .body(),
                Offering.class);

        assertNotEquals(first.next(), second.next());
        assertEquals("plant-1", second.items().get(0).id());
    }

    // -- helpers ----------------------------------------------------------------------------

    private static OdpService paged(int count) {
        return new OdpService(
                Catalog.template(List.of("en")), StaticCatalog.create(offerings(count), List.of(), KEY.clone()));
    }

    private static List<Offering> offerings(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> Catalog.offering("plant-" + index, "Plant " + index))
                .toList();
    }

    private static Offering withCollections(String id, List<String> collectionIds) {
        return new Offering(
                null,
                Odp.VERSION,
                id,
                "Plant",
                null,
                null,
                null,
                null,
                null,
                collectionIds,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }

    private static String cursorOf(OdpService service) {
        String next = OdpJson.parsePage(
                        service.handle(get(OFFERINGS, query("limit", "1"))).body(), Offering.class)
                .next();
        return parse(next).get("cursor").get(0);
    }

    private static long expiryOf(String cursor) {
        String decoded = new String(Base64.getUrlDecoder().decode(cursor.split("\\.")[0]), StandardCharsets.UTF_8);
        return Long.parseLong(decoded.split("\n")[0]);
    }

    private static String payload(long expiry, int offset, int limit, String representation, String path) {
        return expiry + "\n" + offset + "\n" + limit + "\n" + representation + "\n" + path;
    }

    private static String signed(String payload) {
        String encoded = encode(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + encode(sign(encoded, KEY));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] sign(String payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, List<String>> parse(String reference) {
        Map<String, List<String>> parsed = new java.util.LinkedHashMap<>();
        for (String pair : reference.substring(reference.indexOf('?') + 1).split("&")) {
            int equals = pair.indexOf('=');
            parsed.put(pair.substring(0, equals), List.of(pair.substring(equals + 1)));
        }
        return parsed;
    }

    /** Every handler the static catalog registers is reachable through the Service. */
    @Test
    void registersAHandlerForEveryStaticOperation() {
        Map<OdpOperation, OdpService.Endpoint> handlers = StaticCatalog.create(
                List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of(Catalog.collection("plants")));

        assertEquals(
                java.util.Set.of(
                        OdpOperation.LIST_COLLECTIONS,
                        OdpOperation.GET_COLLECTION,
                        OdpOperation.LIST_COLLECTION_OFFERINGS,
                        OdpOperation.LIST_OFFERINGS,
                        OdpOperation.GET_OFFERING),
                handlers.keySet());
    }
}
