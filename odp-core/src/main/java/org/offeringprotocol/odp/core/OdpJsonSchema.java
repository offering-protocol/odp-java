package org.offeringprotocol.odp.core;

import java.util.List;

/** A compiled JSON Schema independent of the selected validation provider. */
@FunctionalInterface
public interface OdpJsonSchema {
    List<ValidationIssue> validate(String json);
}
