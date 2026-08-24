package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum OdpOperation {
    GET_COLLECTION("get-collection", HttpMethods.GET),
    GET_OFFERING("get-offering", HttpMethods.GET),
    LIST_COLLECTION_OFFERINGS("list-collection-offerings", HttpMethods.GET),
    LIST_COLLECTIONS("list-collections", HttpMethods.GET),
    LIST_OFFERINGS("list-offerings", HttpMethods.GET),
    SEARCH_COLLECTIONS("search-collections", "POST"),
    SEARCH_OFFERINGS("search-offerings", "POST");

    private final String encodedValue;
    private final String httpMethod;

    OdpOperation(String value, String method) {
        this.encodedValue = value;
        this.httpMethod = method;
    }

    @JsonValue
    public String value() {
        return encodedValue;
    }

    public String method() {
        return httpMethod;
    }

    @JsonCreator
    public static OdpOperation fromValue(String value) {
        return Arrays.stream(values())
                .filter(operation -> operation.encodedValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported ODP operation: " + value));
    }
}
