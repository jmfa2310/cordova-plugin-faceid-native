package com.company.faceidnative;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PT2 cancelable protected biometric template.
 *
 * Raw FaceNet embeddings are never serialized. A scope-bound secret-keyed
 * random-hyperplane transform converts the 128D normalized FaceNet embedding
 * into a 256-bit binary template.
 *
 * Format:
 *   PT2:<version>:<keyId>:<base64url-code>
 *
 * keyId binds stored templates to the active tenant/company protection context
 * so templates generated with a different company key cannot be loaded by
 * mistake.
 */
public final class TemplateProtector implements AutoCloseable {

    public static final String SCHEME = "PT2_KEYED_RP_256_SCOPE_BOUND";
    public static final int TEMPLATE_BITS = 256;
    public static final int TEMPLATE_BYTES = TEMPLATE_BITS / 8;

    private static final int INPUT_DIM = MobileFaceNetEngine.EMBEDDING_SIZE;
    private static final byte[] PROJECTION_DOMAIN =
            "FaceIDNative|PT2|projection".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_ID_DOMAIN =
            "FaceIDNative|PT2|keyid".getBytes(StandardCharsets.UTF_8);

    private final int version;
    private final String scope;
    private final String keyId;
    private final byte[][] projection;

    public TemplateProtector(
            byte[] masterKey,
            int version,
            String scope
    ) throws Exception {
        if (masterKey == null || masterKey.length < 32) {
            throw new IllegalArgumentException(
                    "Protection key must contain at least 256 bits."
            );
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "TemplateVersion must be >= 1."
            );
        }

        this.version = version;
        this.scope = normalizeScope(scope);
        this.keyId = buildKeyId(masterKey);
        this.projection = buildProjection(masterKey, version);
    }

    public int getVersion() {
        return version;
    }

    public String getScope() {
        return scope;
    }

    public String getKeyId() {
        return keyId;
    }

    public static String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.trim();
        return normalized.isEmpty() ? "default" : normalized;
    }

    /**
     * Derives a scope-bound 256-bit master key.
     * Even if two tenants accidentally configure the same textual secret,
     * their effective master keys differ because the company scope is included.
     */
    public static byte[] deriveMasterKey(
            String protectionKeyText,
            String scope
    ) throws Exception {
        if (protectionKeyText == null ||
                protectionKeyText.trim().length() < 32) {
            throw new IllegalArgumentException(
                    "ProtectionKey must be a random secret with at least 32 characters."
            );
        }

        String normalizedScope = normalizeScope(scope);

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(
                "FaceIDNative|PT2|master|".getBytes(StandardCharsets.UTF_8)
        );
        sha256.update(normalizedScope.getBytes(StandardCharsets.UTF_8));
        sha256.update((byte) '|');
        sha256.update(protectionKeyText.trim().getBytes(StandardCharsets.UTF_8));
        return sha256.digest();
    }

    private static String buildKeyId(byte[] masterKey) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(KEY_ID_DOMAIN);
        sha256.update(masterKey);
        byte[] digest = sha256.digest();

        try {
            byte[] shortId = Arrays.copyOf(digest, 8);
            try {
                return Base64.encodeToString(
                        shortId,
                        Base64.URL_SAFE |
                                Base64.NO_WRAP |
                                Base64.NO_PADDING
                );
            } finally {
                Arrays.fill(shortId, (byte) 0);
            }
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private static byte[][] buildProjection(
            byte[] masterKey,
            int version
    ) throws Exception {
        byte[][] matrix = new byte[TEMPLATE_BITS][INPUT_DIM];

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));

        for (int bit = 0; bit < TEMPLATE_BITS; bit++) {
            int dim = 0;
            int block = 0;

            while (dim < INPUT_DIM) {
                ByteBuffer msg =
                        ByteBuffer.allocate(PROJECTION_DOMAIN.length + 12)
                                .order(ByteOrder.BIG_ENDIAN);

                msg.put(PROJECTION_DOMAIN);
                msg.putInt(version);
                msg.putInt(bit);
                msg.putInt(block++);

                byte[] digest = mac.doFinal(msg.array());

                try {
                    for (int i = 0;
                         i < digest.length && dim < INPUT_DIM;
                         i++, dim++) {
                        matrix[bit][dim] =
                                ((digest[i] & 0x01) == 0)
                                        ? (byte) -1
                                        : (byte) 1;
                    }
                } finally {
                    Arrays.fill(digest, (byte) 0);
                }
            }
        }

        return matrix;
    }

    public byte[] protect(float[] embedding) {
        if (embedding == null || embedding.length != INPUT_DIM) {
            throw new IllegalArgumentException(
                    "Unexpected FaceNet embedding size."
            );
        }

        byte[] code = new byte[TEMPLATE_BYTES];

        for (int bit = 0; bit < TEMPLATE_BITS; bit++) {
            double dot = 0.0;
            byte[] hyperplane = projection[bit];

            for (int d = 0; d < INPUT_DIM; d++) {
                dot += embedding[d] * hyperplane[d];
            }

            if (dot >= 0.0) {
                code[bit >>> 3] |= (byte) (1 << (bit & 7));
            }
        }

        return code;
    }

    public String encode(byte[] code) {
        validateCode(code);

        return "PT2:" +
                version + ":" +
                keyId + ":" +
                Base64.encodeToString(
                        code,
                        Base64.URL_SAFE |
                                Base64.NO_WRAP |
                                Base64.NO_PADDING
                );
    }

    public byte[] decode(String protectedTemplate) {
        if (protectedTemplate == null) {
            return null;
        }

        try {
            String[] parts =
                    protectedTemplate.trim().split(":", 4);

            if (parts.length != 4 ||
                    !"PT2".equals(parts[0])) {
                return null;
            }

            int incomingVersion =
                    Integer.parseInt(parts[1]);

            if (incomingVersion != version ||
                    !keyId.equals(parts[2])) {
                return null;
            }

            byte[] decoded =
                    Base64.decode(
                            parts[3],
                            Base64.URL_SAFE |
                                    Base64.NO_WRAP |
                                    Base64.NO_PADDING
                    );

            if (decoded.length != TEMPLATE_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                return null;
            }

            return decoded;

        } catch (Exception ignored) {
            return null;
        }
    }

    public double similarity(byte[] a, byte[] b) {
        validateCode(a);
        validateCode(b);

        int different = 0;

        for (int i = 0; i < TEMPLATE_BYTES; i++) {
            different += Integer.bitCount(
                    (a[i] ^ b[i]) & 0xFF
            );
        }

        double agreement =
                1.0 -
                        (different /
                                (double) TEMPLATE_BITS);

        double estimatedCosine =
                Math.cos(
                        Math.PI *
                                (1.0 - agreement)
                );

        return Math.max(
                -1.0,
                Math.min(1.0, estimatedCosine)
        );
    }

    private static void validateCode(byte[] code) {
        if (code == null ||
                code.length != TEMPLATE_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid protected-template size."
            );
        }
    }

    @Override
    public void close() {
        for (byte[] row : projection) {
            Arrays.fill(row, (byte) 0);
        }
    }
}
