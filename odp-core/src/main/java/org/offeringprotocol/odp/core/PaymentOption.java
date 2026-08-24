package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum PaymentOption {
    ALGORAND("algorand"),
    APTOS("aptos"),
    ARBITRUM("arbitrum"),
    AVALANCHE("avalanche"),
    BASE("base"),
    CARD("card"),
    ETHEREUM("ethereum"),
    HEDERA("hedera"),
    INFLOW("inflow"),
    LIGHTNING("lightning"),
    POLYGON("polygon"),
    SOLANA("solana"),
    STELLAR("stellar"),
    STRIPE("stripe"),
    TEMPO("tempo"),
    TON("ton");

    private final String encodedValue;

    PaymentOption(String value) {
        this.encodedValue = value;
    }

    @JsonValue
    public String value() {
        return encodedValue;
    }

    @JsonCreator
    public static PaymentOption fromValue(String value) {
        return Arrays.stream(values())
                .filter(option -> option.encodedValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported payment option: " + value));
    }
}
