package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Verifies solder.py's detached ECDSA signature over canonical JSON. */
final class ManifestVerifier {
    private static final String ALGORITHM = "SHA256withECDSA";
    private static final String CURVE = "secp256r1";
    private static final String PUBLIC_KEY_FORMAT = "X.509";
    private static final String ENCODING = "base64";
    private static final int MAX_KEY_BYTES = 4096;
    private static final int MAX_SIGNATURE_BYTES = 1024;

    private ManifestVerifier() {
    }

    static void requireConfigured(LoaderConfig.ManifestVerification configuration)
        throws LoaderException {

        if (configuration == null) {
            throw new LoaderException(
                "Missing manifestVerification in config/solderpy-loader.json. " +
                "Re-export or reinstall the modpack to pin its solder.py signing key.");
        }
        validateConfiguration(configuration);
    }

    static void validateConfiguration(LoaderConfig.ManifestVerification configuration)
        throws LoaderException {

        if (!configuration.required || !ALGORITHM.equals(configuration.algorithm) ||
            !CURVE.equals(configuration.curve) ||
            !PUBLIC_KEY_FORMAT.equals(configuration.publicKeyFormat) ||
            !ENCODING.equals(configuration.encoding) || configuration.keyId == null ||
            !configuration.keyId.matches("sha256:[0-9a-f]{64}") ||
            configuration.publicKey == null || configuration.publicKey.trim().isEmpty()) {
            throw new LoaderException(
                "manifestVerification must require SHA256withECDSA using a base64 X.509 " +
                "secp256r1 public key and its sha256 keyId");
        }
        try {
            byte[] encoded = decodeBase64(
                configuration.publicKey, MAX_KEY_BYTES, "manifest public key");
            String actualKeyId = "sha256:" + hex(
                MessageDigest.getInstance("SHA-256").digest(encoded));
            if (!actualKeyId.equals(configuration.keyId)) {
                throw new LoaderException(
                    "manifestVerification keyId does not match its publicKey");
            }
            java.security.PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(encoded));
            if (!(key instanceof ECPublicKey) ||
                !isExpectedCurve(((ECPublicKey) key).getParams())) {
                throw new LoaderException(
                    "manifestVerification publicKey is not a secp256r1 EC key");
            }
            configuration.parsedPublicKey = key;
        } catch (LoaderException e) {
            throw e;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new LoaderException(
                "manifestVerification contains an invalid public key", e);
        }
    }

    static void verify(
        JsonObject manifest, LoaderConfig.ManifestVerification configuration)
        throws LoaderException {

        requireConfigured(configuration);
        if (manifest == null) {
            throw new LoaderException("Bootstrap manifest response is empty");
        }
        JsonObject signature = object(manifest.get("signature"));
        String algorithm = string(signature, "algorithm");
        String keyId = string(signature, "key_id");
        String encoding = string(signature, "encoding");
        String value = string(signature, "value");
        if (!ALGORITHM.equals(algorithm) || !ENCODING.equals(encoding) ||
            !configuration.keyId.equals(keyId)) {
            throw new LoaderException(
                "Bootstrap manifest signature does not match the pinned signing configuration");
        }

        JsonObject unsigned = manifest.deepCopy();
        unsigned.remove("signature");
        byte[] payload = canonicalPayload(unsigned);
        byte[] encodedSignature = decodeBase64(
            value, MAX_SIGNATURE_BYTES, "bootstrap manifest signature");
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(configuration.parsedPublicKey);
            verifier.update(payload);
            if (!verifier.verify(encodedSignature)) {
                throw new LoaderException(
                    "Bootstrap manifest signature verification failed; the response may be " +
                    "damaged or from an untrusted server");
            }
        } catch (LoaderException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new LoaderException(
                "This Java runtime could not verify the bootstrap manifest signature", e);
        }
    }

    static byte[] canonicalPayload(JsonElement element) throws LoaderException {
        StringBuilder result = new StringBuilder();
        appendCanonical(element, result);
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCanonical(JsonElement element, StringBuilder output)
        throws LoaderException {

        if (element == null || element.isJsonNull()) {
            output.append("null");
            return;
        }
        if (element.isJsonObject()) {
            output.append('{');
            List<Map.Entry<String, JsonElement>> entries =
                new ArrayList<Map.Entry<String, JsonElement>>(
                    element.getAsJsonObject().entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<String, JsonElement>>() {
                @Override
                public int compare(
                    Map.Entry<String, JsonElement> left,
                    Map.Entry<String, JsonElement> right) {

                    return compareCodePoints(left.getKey(), right.getKey());
                }
            });
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : entries) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendString(entry.getKey(), output);
                output.append(':');
                appendCanonical(entry.getValue(), output);
            }
            output.append('}');
            return;
        }
        if (element.isJsonArray()) {
            output.append('[');
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                appendCanonical(array.get(index), output);
            }
            output.append(']');
            return;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString()) {
            appendString(primitive.getAsString(), output);
        } else if (primitive.isBoolean()) {
            output.append(primitive.getAsBoolean() ? "true" : "false");
        } else if (primitive.isNumber()) {
            String number = primitive.getAsNumber().toString();
            if (!number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) {
                throw new LoaderException(
                    "Bootstrap manifest contains a non-canonical JSON number");
            }
            output.append(number);
        } else {
            throw new LoaderException("Bootstrap manifest contains an unsupported JSON value");
        }
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20 || character >= 0x7f) {
                        output.append("\\u");
                        String hex = Integer.toHexString(character);
                        for (int padding = hex.length(); padding < 4; padding++) {
                            output.append('0');
                        }
                        output.append(hex);
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) {
                return leftPoint < rightPoint ? -1 : 1;
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private static JsonObject object(JsonElement value) throws LoaderException {
        if (value == null || !value.isJsonObject()) {
            throw new LoaderException("Bootstrap manifest is missing its signature object");
        }
        return value.getAsJsonObject();
    }

    private static String string(JsonObject object, String name) throws LoaderException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() ||
            !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) {
            throw new LoaderException(
                "Bootstrap manifest signature is missing " + name);
        }
        return value.getAsString();
    }

    private static byte[] decodeBase64(String value, int maximum, String description)
        throws LoaderException {

        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0 || decoded.length > maximum) {
                throw new IllegalArgumentException();
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new LoaderException(description + " is not valid base64", e);
        }
    }

    private static boolean isExpectedCurve(ECParameterSpec actual)
        throws GeneralSecurityException {

        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(CURVE));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        return actual != null &&
            actual.getCurve().getField().getFieldSize() ==
                expected.getCurve().getField().getFieldSize() &&
            actual.getCurve().getA().equals(expected.getCurve().getA()) &&
            actual.getCurve().getB().equals(expected.getCurve().getB()) &&
            actual.getGenerator().equals(expected.getGenerator()) &&
            actual.getOrder().equals(expected.getOrder()) &&
            actual.getCofactor() == expected.getCofactor();
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }
}
