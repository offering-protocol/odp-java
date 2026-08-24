package org.offeringprotocol.odp.directory;

import java.net.http.HttpHeaders;

public final class DirectoryRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int responseStatus;
    private final transient HttpHeaders responseHeaders;

    public DirectoryRequestException(int status, String message, HttpHeaders headers) {
        super(message);
        this.responseStatus = status;
        this.responseHeaders = headers;
    }

    public int status() {
        return responseStatus;
    }

    public HttpHeaders headers() {
        return responseHeaders;
    }
}
