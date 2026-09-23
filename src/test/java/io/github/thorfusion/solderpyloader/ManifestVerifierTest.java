package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestVerifierTest {
    @Test
    void canonicalJsonMatchesTheDocumentedPythonSerialization() throws Exception {
        JsonObject object = new JsonObject();
        object.addProperty("z", "line\n");
        JsonArray array = new JsonArray();
        array.add(true);
        array.add((String) null);
        array.add(1);
        object.add("array", array);
        object.addProperty("a", new String(new int[] {0x00e5, 0x1f600}, 0, 2));
        object.addProperty("delete", String.valueOf((char) 0x7f));

        String canonical = new String(
            ManifestVerifier.canonicalPayload(object), StandardCharsets.UTF_8);

        assertEquals(
            "{\"a\":\"\\u00e5\\ud83d\\ude00\",\"array\":[true,null,1]," +
                "\"delete\":\"\\u007f\",\"z\":\"line\\n\"}", canonical);
    }

    @Test
    void verifiesSignedManifestAndRejectsTampering() throws Exception {
        KeyPair keys = ManifestSigningTestSupport.keyPair();
        LoaderConfig.ManifestVerification configuration =
            ManifestSigningTestSupport.configuration(keys);
        JsonObject manifest = JsonSupport.GSON.fromJson(
            "{\"schema\":\"solder.py/bootstrap\",\"packages\":[]," +
                "\"manifest_hash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", JsonObject.class);
        ManifestSigningTestSupport.sign(manifest, keys);

        ManifestVerifier.verify(manifest, configuration);

        manifest.addProperty("target", "tampered");
        LoaderException error = assertThrows(LoaderException.class,
            () -> ManifestVerifier.verify(manifest, configuration));
        assertTrue(error.getMessage().contains("signature verification failed"));
    }

    @Test
    void verifiesAFixtureSignedBySolderPysPythonImplementation() throws Exception {
        LoaderConfig.ManifestVerification configuration =
            new LoaderConfig.ManifestVerification();
        configuration.required = true;
        configuration.algorithm = "SHA256withECDSA";
        configuration.curve = "secp256r1";
        configuration.publicKeyFormat = "X.509";
        configuration.encoding = "base64";
        configuration.keyId =
            "sha256:28eab411daeeb407180cc0d75e5645a3ac0027643e32c6b51c107dcf772aaf53";
        configuration.publicKey =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUi31CUmQ3ioSzMLXNwwPBucAlKhT" +
            "naW4iy2WPyk2UNaH5TSg163Ant8CF3SGkaW/GLap2y9KjvU4LYxf5D1ALg==";
        ManifestVerifier.validateConfiguration(configuration);

        JsonObject manifest = JsonSupport.GSON.fromJson(
            "{\"schema\":\"solder.py/bootstrap\",\"schema_version\":1," +
                "\"target\":\"client\",\"source\":\"hybrid\",\"packages\":[{" +
                "\"name\":\"example\",\"version\":\"1.0\"," +
                "\"description\":\"Norwegian \\u00e5 and emoji \\ud83d\\ude00\"," +
                "\"filesize\":24680}],\"manifest_hash\":\"" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"," +
                "\"changes\":{\"added\":[1],\"selection_changed\":false}}",
            JsonObject.class);
        JsonObject signature = new JsonObject();
        signature.addProperty("algorithm", "SHA256withECDSA");
        signature.addProperty("key_id", configuration.keyId);
        signature.addProperty("encoding", "base64");
        signature.addProperty("value",
            "MEQCIGIE3+UbUZ3BsETylv2S/9bMG3hDEAmcknhDbXuNFDOzAiBaR9zYIPQZ" +
                "BXdtmNe+MqzuDolUpsBoUEcwzay9Ue2xDQ==");
        manifest.add("signature", signature);

        ManifestVerifier.verify(manifest, configuration);
    }

    @Test
    void requiresAPinnedExportedKey() {
        LoaderException error = assertThrows(LoaderException.class,
            () -> ManifestVerifier.requireConfigured(null));

        assertTrue(error.getMessage().contains("Re-export or reinstall"));
    }
}
