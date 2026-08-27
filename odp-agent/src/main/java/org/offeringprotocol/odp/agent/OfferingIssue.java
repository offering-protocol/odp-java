package org.offeringprotocol.odp.agent;

public record OfferingIssue(Scope scope, String message, String actionId) {
    public enum Scope {
        ACTION,
        ATTRIBUTE_SCHEMA,
        ATTRIBUTES
    }
}
