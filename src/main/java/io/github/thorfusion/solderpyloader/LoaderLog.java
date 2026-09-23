package io.github.thorfusion.solderpyloader;

final class LoaderLog {
    private static final String PREFIX = "[SolderPy Modpack Loader] ";

    private LoaderLog() {
    }

    static void info(String message) {
        System.out.println(PREFIX + message);
    }

    static void warn(String message) {
        System.err.println(PREFIX + "WARN: " + message);
    }

    static void error(String message, Throwable error) {
        System.err.println(PREFIX + "ERROR: " + message);
        if (Boolean.getBoolean("solderpy.loader.debug")) {
            error.printStackTrace(System.err);
        }
    }
}
