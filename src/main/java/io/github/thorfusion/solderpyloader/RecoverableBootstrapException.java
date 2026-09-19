package io.github.thorfusion.solderpyloader;

/** A bootstrap failure known to have left the previously installed files intact. */
final class RecoverableBootstrapException extends LoaderException {
    RecoverableBootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
