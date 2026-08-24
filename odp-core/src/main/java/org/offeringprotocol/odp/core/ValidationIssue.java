package org.offeringprotocol.odp.core;

import java.io.Serializable;
import java.util.Map;

public record ValidationIssue(String keyword, String message, Map<String, String> params, String path)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public ValidationIssue {
        params = Copies.map(params);
    }
}
