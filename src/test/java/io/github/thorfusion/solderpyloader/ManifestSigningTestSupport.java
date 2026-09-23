package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

final class ManifestSigningTestSupport {
    private ManifestSigningTestSupport() {
    }

    static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static LoaderConfig.ManifestVerification configuration(KeyPair keys)
        throws Exception {

        byte[] encoded = keys.getPublic().getEncoded();
        LoaderConfig.ManifestVerification result =
            new LoaderConfig.ManifestVerification();
        result.required = true;
        result.algorithm = "SHA256withECDSA";
        result.curve = "secp256r1";
        result.publicKeyFormat = "X.509";
        result.encoding = "base64";
        result.keyId = "sha256:" + hex(
            MessageDigest.getInstance("SHA-256").digest(encoded));
        result.publicKey = Base64.getEncoder().encodeToString(encoded);
        ManifestVerifier.validateConfiguration(result);
        return result;
    }

    static String configurationJson(KeyPair keys) throws Exception {
        return JsonSupport.GSON.toJson(configuration(keys));
    }

    static JsonObject sign(JsonObject manifest, KeyPair keys) throws Exception {
        manifest.remove("signature");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(ManifestVerifier.canonicalPayload(manifest));

        JsonObject signature = new JsonObject();
        signature.addProperty("algorithm", "SHA256withECDSA");
        signature.addProperty("key_id", configuration(keys).keyId);
        signature.addProperty("encoding", "base64");
        signature.addProperty("value", Base64.getEncoder().encodeToString(signer.sign()));
        manifest.add("signature", signature);
        return manifest;
    }

    static byte[] jsonBytes(JsonObject object) {
        return JsonSupport.GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
