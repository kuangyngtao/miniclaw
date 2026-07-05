package com.miniclaw.im;

public class ImException extends Exception {

    public ImException(String message) {
        super(message);
    }

    public ImException(String message, Throwable cause) {
        super(message, cause);
    }
}
