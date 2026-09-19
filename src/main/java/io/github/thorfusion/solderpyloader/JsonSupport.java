package io.github.thorfusion.solderpyloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

final class JsonSupport {
    static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create();

    private JsonSupport() {
    }
}

