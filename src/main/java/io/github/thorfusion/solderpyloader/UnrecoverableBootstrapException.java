package io.github.thorfusion.solderpyloader;

/** A bootstrap failure that may have left the installed files inconsistent. */
final class UnrecoverableBootstrapException extends LoaderException {
    UnrecoverableBootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
