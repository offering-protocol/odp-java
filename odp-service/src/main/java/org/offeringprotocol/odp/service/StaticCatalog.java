package org.offeringprotocol.odp.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Collection;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.Page;

/** In-memory catalog handlers for small ODP Services. */
public final class StaticCatalog {
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final int CURSOR_PARTS = 2;
    private static final int CURSOR_VALUES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private StaticCatalog() {}

    public static Map<OdpOperation, OdpService.Endpoint> create(
            List<Offering> offerings, List<Collection> collections) {
        byte[] continuationKey = new byte[MINIMUM_KEY_BYTES];
        RANDOM.nextBytes(continuationKey);
        return create(offerings, collections, continuationKey);
    }

    public static Map<OdpOperation, OdpService.Endpoint> create(
            List<Offering> offerings, List<Collection> collections, byte[] continuationKey) {
        if (continuationKey.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException("continuationKey must contain at least 32 bytes");
        }
        List<Offering> catalogOfferings = List.copyOf(offerings);
        List<Collection> catalogCollections = List.copyOf(collections);
        byte[] key = continuationKey.clone();
        Map<String, Offering> offeringsById = unique(catalogOfferings, Offering::id, "Offering");
        Map<String, Collection> collectionsById = unique(catalogCollections, Collection::id, "Collection");
        Map<OdpOperation, OdpService.Endpoint> handlers = new LinkedHashMap<>();
        handlers.put(
                OdpOperation.LIST_OFFERINGS,
                endpoint(request -> page(catalogOfferings, request, StaticCatalog::terseOfferingItem, key)));
        handlers.put(
                OdpOperation.GET_OFFERING,
                endpoint(request ->
                        represent(offeringsById.get(request.identifier()), request, StaticCatalog::terseOffering)));
        if (!catalogCollections.isEmpty()) {
            handlers.put(
                    OdpOperation.LIST_COLLECTIONS,
                    endpoint(request -> page(catalogCollections, request, StaticCatalog::terseCollectionItem, key)));
            handlers.put(
                    OdpOperation.GET_COLLECTION,
                    endpoint(request -> represent(
                            collectionsById.get(request.identifier()), request, StaticCatalog::terseCollection)));
            handlers.put(OdpOperation.LIST_COLLECTION_OFFERINGS, endpoint(request -> {
                if (!collectionsById.containsKey(request.identifier())) {
                    return null;
                }
                List<Offering> matches = catalogOfferings.stream()
                        .filter(offering -> offering.collectionIds() != null
                                && offering.collectionIds().contains(request.identifier()))
                        .toList();
                return page(matches, request, StaticCatalog::terseOfferingItem, key);
            }));
        }
        return Map.copyOf(handlers);
    }

    private static OdpService.Endpoint endpoint(CatalogHandler handler) {
        return new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, handler);
    }

    private static <T> Page<T> page(
            List<T> values, CatalogRequest request, Function<T, T> terseRepresentation, byte[] continuationKey) {
        int limit = request.limit() == null ? 50 : request.limit();
        int offset = request.cursor() == null ? 0 : decodeCursor(request.cursor(), request, limit, continuationKey);
        List<T> items = values.stream()
                .skip(offset)
                .limit(limit)
                .map(value -> "full".equals(request.representation()) ? value : terseRepresentation.apply(value))
                .toList();
        int nextOffset = offset + items.size();
        String next = nextOffset >= values.size()
                ? null
                : request.request().path() + "?cursor="
                        + encodeCursor(nextOffset, request, limit, continuationKey)
                        + "&representation=" + request.representation() + "&limit=" + limit;
        return new Page<>(null, Odp.VERSION, items, next, Map.of());
    }

    private static String encodeCursor(int offset, CatalogRequest request, int limit, byte[] continuationKey) {
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((Instant.now().plusSeconds(3600).getEpochSecond() + "\n" + offset + "\n" + limit + "\n"
                                + request.representation() + "\n"
                                + request.request().path())
                        .getBytes(StandardCharsets.UTF_8));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload, continuationKey));
    }

    private static int decodeCursor(String cursor, CatalogRequest request, int limit, byte[] continuationKey) {
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != CURSOR_PARTS) {
            throw expiredCursor();
        }
        byte[] supplied;
        String decoded;
        try {
            supplied = Base64.getUrlDecoder().decode(parts[1]);
            decoded = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw expiredCursor(exception);
        }
        if (!MessageDigest.isEqual(supplied, sign(parts[0], continuationKey))) {
            throw expiredCursor();
        }
        String[] values = decoded.split("\n", -1);
        if (values.length != CURSOR_VALUES) {
            throw expiredCursor();
        }
        try {
            int offset = Integer.parseInt(values[1]);
            if (Long.parseLong(values[0]) < Instant.now().getEpochSecond()
                    || offset < 0
                    || Integer.parseInt(values[2]) != limit
                    || !request.representation().equals(values[3])
                    || !request.request().path().equals(values[4])) {
                throw expiredCursor();
            }
            return offset;
        } catch (NumberFormatException exception) {
            throw expiredCursor(exception);
        }
    }

    private static byte[] sign(String payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static OdpServiceException expiredCursor() {
        return new OdpServiceException(410, "CONTINUATION_EXPIRED", "Continuation is unavailable");
    }

    private static OdpServiceException expiredCursor(Throwable cause) {
        OdpServiceException exception = expiredCursor();
        exception.initCause(cause);
        return exception;
    }

    private static <T> T represent(T value, CatalogRequest request, Function<T, T> terseRepresentation) {
        if (value == null || "full".equals(request.representation())) {
            return value;
        }
        return terseRepresentation.apply(value);
    }

    private static Offering terseOffering(Offering value) {
        return terseOffering(value, false);
    }

    private static Offering terseOfferingItem(Offering value) {
        return terseOffering(value, true);
    }

    private static Offering terseOffering(Offering value, boolean embedded) {
        return new Offering(
                value.authExpands(),
                embedded ? null : value.odpVersion(),
                value.id(),
                value.name(),
                value.description(),
                value.images() == null ? null : value.images().stream().limit(1).toList(),
                value.language(),
                value.localizations(),
                value.webUrl(),
                value.collectionIds(),
                value.price(),
                value.schema(),
                value.attributes(),
                null,
                null,
                value.additional());
    }

    private static Collection terseCollection(Collection value) {
        return terseCollection(value, false);
    }

    private static Collection terseCollectionItem(Collection value) {
        return terseCollection(value, true);
    }

    private static Collection terseCollection(Collection value, boolean embedded) {
        return new Collection(
                value.authExpands(),
                embedded ? null : value.odpVersion(),
                value.id(),
                value.name(),
                value.description(),
                value.images() == null ? null : value.images().stream().limit(1).toList(),
                value.language(),
                value.localizations(),
                value.parentIds(),
                value.webUrl(),
                value.searchCapabilities(),
                null,
                value.additional());
    }

    private static <T> Map<String, T> unique(List<T> values, Function<T, String> identifier, String resourceType) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            if (result.put(identifier.apply(value), value) != null) {
                throw new IllegalArgumentException(resourceType + " identifiers must be unique");
            }
        }
        return result;
    }
}
