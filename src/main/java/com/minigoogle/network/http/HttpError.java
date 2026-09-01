package com.minigoogle.network.http;

/** Thrown by a handler to produce a specific HTTP status and error code. */
public class HttpError extends RuntimeException {

    private final int status;
    private final String code;

    public HttpError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
