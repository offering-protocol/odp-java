package org.offeringprotocol.odp.service;

public final class OdpServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int responseStatus;
    private final String problemCode;

    public OdpServiceException(int status, String code, String message) {
        super(message);
        this.responseStatus = status;
        this.problemCode = code;
    }

    public int status() {
        return responseStatus;
    }

    public String code() {
        return problemCode;
    }
}
