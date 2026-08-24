package org.offeringprotocol.odp.agent;

import java.net.http.HttpHeaders;
import org.offeringprotocol.odp.core.ProblemDetails;

public final class OdpRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int responseStatus;
    private final transient HttpHeaders responseHeaders;
    private final transient ProblemDetails problemDetails;

    public OdpRequestException(int status, String message, HttpHeaders headers, ProblemDetails problem) {
        super(message);
        this.responseStatus = status;
        this.responseHeaders = headers;
        this.problemDetails = problem;
    }

    public int status() {
        return responseStatus;
    }

    public HttpHeaders headers() {
        return responseHeaders;
    }

    public ProblemDetails problem() {
        return problemDetails;
    }
}
