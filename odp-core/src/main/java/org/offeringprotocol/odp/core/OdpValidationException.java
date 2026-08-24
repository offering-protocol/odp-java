package org.offeringprotocol.odp.core;

import java.util.List;

public final class OdpValidationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String invalidDocumentType;
    private final List<ValidationIssue> validationIssues;

    public OdpValidationException(String documentType, List<ValidationIssue> issues) {
        this(documentType, issues, null);
    }

    public OdpValidationException(String documentType, List<ValidationIssue> issues, Throwable cause) {
        super("Invalid ODP " + documentType, cause);
        this.invalidDocumentType = documentType;
        this.validationIssues = List.copyOf(issues);
    }

    public String documentType() {
        return invalidDocumentType;
    }

    public List<ValidationIssue> issues() {
        return validationIssues;
    }
}
