package org.offeringprotocol.odp.core;

import java.util.Map;

/** JSON encoding and schema validation supplied by an application-selected adapter. */
public interface OdpJsonProvider {
    Map<String, OdpJsonSchema> compileSchemas(Map<String, String> schemas);

    <T> T decode(String json, Class<T> type);

    <T> Page<T> decodePage(String json, Class<T> itemType);

    OdpJsonNode parseTree(String json);

    <T> T treeToValue(OdpJsonNode node, Class<T> type);

    OdpJsonNode valueToTree(Object value);

    String write(Object value);
}
