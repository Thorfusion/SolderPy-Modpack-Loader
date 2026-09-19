package io.github.thorfusion.solderpyloader;

/** A deliberate user cancellation which must not be bypassed by fail-open mode. */
final class LaunchCancelledException extends LoaderException {
    LaunchCancelledException(String message) {
        super(message);
    }
}
