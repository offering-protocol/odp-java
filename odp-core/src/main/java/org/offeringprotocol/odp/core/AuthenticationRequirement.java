package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum AuthenticationRequirement {
    NOT_REQUIRED("not-required"),
    OPTIONAL("optional"),
    REQUIRED("required");

    private final String encodedValue;

    AuthenticationRequirement(String value) {
        this.encodedValue = value;
    }

    @JsonValue
    public String value() {
        return encodedValue;
    }

    @JsonCreator
    public static AuthenticationRequirement fromValue(String value) {
        return Arrays.stream(values())
                .filter(requirement -> requirement.encodedValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported authentication requirement: " + value));
    }
}
