package io.github.thorfusion.solderpyloader;

/** A user-actionable bootstrap failure. */
public class LoaderException extends Exception {
    public LoaderException(String message) {
        super(message);
    }

    public LoaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
