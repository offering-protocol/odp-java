package org.offeringprotocol.odp.service;

/**
 * A failure a handler chose to publish. Its message reaches the caller as the problem's
 * {@code detail}, so it describes the request rather than the Service's internal state; a failure
 * the Service did not ask for becomes an {@code INTERNAL_ERROR} carrying no message at all.
 */
public final class OdpServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int responseStatus;
    private final String problemCode;
    private final Integer retryAfterSeconds;
    private final String allowedMethods;

    public OdpServiceException(int status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public OdpServiceException(int status, String code, String message, Throwable cause) {
        this(status, code, message, cause, null, null);
    }

    /**
     * @param retryAfter seconds a caller should wait before retrying. A {@code 429} carries the field
     *     whether or not one is given, because ERR-32 requires it; a {@code 503} carries it too.
     */
    public static OdpServiceException retryable(int status, String code, String message, int retryAfter) {
        return new OdpServiceException(status, code, message, null, retryAfter, null);
    }

    /** A 405, which RFC 9110 15.5.6 requires to name the methods the resource does allow. */
    static OdpServiceException notAllowed(String message, String allow) {
        return new OdpServiceException(405, "METHOD_NOT_ALLOWED", message, null, null, allow);
    }

    private OdpServiceException(
            int status, String code, String message, Throwable cause, Integer retryAfter, String allow) {
        super(message, cause);
        this.responseStatus = status;
        this.problemCode = code;
        this.retryAfterSeconds = retryAfter;
        this.allowedMethods = allow;
    }

    public int status() {
        return responseStatus;
    }

    public String code() {
        return problemCode;
    }

    /** Seconds to wait before retrying, when the Service knows; otherwise null. */
    public Integer retryAfter() {
        return retryAfterSeconds;
    }

    /** The {@code Allow} field value this failure carries, when it is a 405; otherwise null. */
    public String allow() {
        return allowedMethods;
    }
}
