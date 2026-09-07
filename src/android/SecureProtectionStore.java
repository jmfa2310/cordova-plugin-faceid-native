package com.company.faceidnative;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores one protected-template master key per company/tenant scope.
 *
 * Each 32-byte scope-derived master key is encrypted with a non-exportable
 * Android Keystore AES key. The active scope identifier is not secret but is
 * also kept in app-private preferences.
 */
public final class SecureProtectionStore {

    private static final String PREFS = "FaceIDNativeSecureV2";
    private static final String P_ACTIVE_SCOPE = "active_scope";
    private static final String KEY_ALIAS =
            "FaceIDNative.TemplateKeyWrap.v2";

    private SecureProtectionStore() {}

    public static final class SavedProtection {
        public final byte[] masterKey;
        public final int version;
        public final String scope;

        SavedProtection(
                byte[] masterKey,
                int version,
                String scope
        ) {
            this.masterKey = masterKey;
            this.version = version;
            this.scope = scope;
        }
    }

    public static boolean existsActive(Context context) {
        try {
            String scope = getActiveScope(context);
            return scope != null && exists(context, scope);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean exists(
            Context context,
            String scope
    ) {
        String id = scopeId(scope);
        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );

        return prefs.contains("cipher_" + id) &&
                prefs.contains("iv_" + id) &&
                prefs.contains("version_" + id);
    }

    public static void save(
            Context context,
            String scope,
            byte[] masterKey,
            int version
    ) throws Exception {

        if (masterKey == null ||
                masterKey.length < 32) {
            throw new IllegalArgumentException(
                    "Invalid protection key."
            );
        }

        String normalizedScope =
                TemplateProtector.normalizeScope(scope);

        String id = scopeId(normalizedScope);

        SecretKey wrappingKey =
                getOrCreateWrappingKey();

        Cipher cipher =
                Cipher.getInstance(
                        "AES/GCM/NoPadding"
                );

        // AndroidKeyStore keys created with
        // setRandomizedEncryptionRequired(true) MUST generate
        // their own IV for encryption. Supplying a caller IV
        // causes: "Caller-provided IV not permitted".
        cipher.init(
                Cipher.ENCRYPT_MODE,
                wrappingKey
        );

        byte[] iv = cipher.getIV();

        if (iv == null || iv.length == 0) {
            throw new IllegalStateException(
                    "AndroidKeyStore did not generate an AES-GCM IV."
            );
        }

        byte[] encrypted =
                cipher.doFinal(masterKey);

        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(
                            PREFS,
                            Context.MODE_PRIVATE
                    );

            boolean ok =
                    prefs.edit()
                            .putString(
                                    "cipher_" + id,
                                    Base64.encodeToString(
                                            encrypted,
                                            Base64.NO_WRAP
                                    )
                            )
                            .putString(
                                    "iv_" + id,
                                    Base64.encodeToString(
                                            iv,
                                            Base64.NO_WRAP
                                    )
                            )
                            .putInt(
                                    "version_" + id,
                                    version
                            )
                            .putString(
                                    "scope_" + id,
                                    normalizedScope
                            )
                            .putString(
                                    P_ACTIVE_SCOPE,
                                    normalizedScope
                            )
                            .commit();

            if (!ok) {
                throw new IllegalStateException(
                        "Could not persist protection configuration."
                );
            }

        } finally {
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static SavedProtection loadActive(
            Context context
    ) throws Exception {
        String scope = getActiveScope(context);

        if (scope == null) {
            return null;
        }

        return load(context, scope);
    }

    public static SavedProtection load(
            Context context,
            String scope
    ) throws Exception {

        String normalizedScope =
                TemplateProtector.normalizeScope(scope);
        String id = scopeId(normalizedScope);

        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );

        String encryptedText =
                prefs.getString(
                        "cipher_" + id,
                        null
                );

        String ivText =
                prefs.getString(
                        "iv_" + id,
                        null
                );

        int version =
                prefs.getInt(
                        "version_" + id,
                        0
                );

        String savedScope =
                prefs.getString(
                        "scope_" + id,
                        normalizedScope
                );

        if (encryptedText == null ||
                ivText == null ||
                version < 1) {
            return null;
        }

        byte[] encrypted =
                Base64.decode(
                        encryptedText,
                        Base64.NO_WRAP
                );

        byte[] iv =
                Base64.decode(
                        ivText,
                        Base64.NO_WRAP
                );

        try {
            KeyStore keyStore =
                    KeyStore.getInstance(
                            "AndroidKeyStore"
                    );

            keyStore.load(null);

            SecretKey wrappingKey =
                    (SecretKey) keyStore.getKey(
                            KEY_ALIAS,
                            null
                    );

            if (wrappingKey == null) {
                return null;
            }

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding"
                    );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    wrappingKey,
                    new GCMParameterSpec(
                            128,
                            iv
                    )
            );

            byte[] masterKey =
                    cipher.doFinal(encrypted);

            return new SavedProtection(
                    masterKey,
                    version,
                    savedScope
            );

        } finally {
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static boolean activate(
            Context context,
            String scope
    ) {
        String normalizedScope =
                TemplateProtector.normalizeScope(scope);

        if (!exists(context, normalizedScope)) {
            return false;
        }

        return context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                )
                .edit()
                .putString(
                        P_ACTIVE_SCOPE,
                        normalizedScope
                )
                .commit();
    }

    public static String getActiveScope(
            Context context
    ) {
        return context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                )
                .getString(
                        P_ACTIVE_SCOPE,
                        null
                );
    }

    public static void clear(
            Context context,
            String scope
    ) {
        String normalizedScope =
                TemplateProtector.normalizeScope(scope);

        String id = scopeId(normalizedScope);

        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );

        SharedPreferences.Editor editor =
                prefs.edit()
                        .remove("cipher_" + id)
                        .remove("iv_" + id)
                        .remove("version_" + id)
                        .remove("scope_" + id);

        String active =
                prefs.getString(
                        P_ACTIVE_SCOPE,
                        null
                );

        if (normalizedScope.equals(active)) {
            editor.remove(P_ACTIVE_SCOPE);
        }

        editor.commit();
    }

    public static void clearAll(Context context) {
        try {
            context.getSharedPreferences(
                            PREFS,
                            Context.MODE_PRIVATE
                    )
                    .edit()
                    .clear()
                    .commit();
        } catch (Exception ignored) {}

        try {
            KeyStore keyStore =
                    KeyStore.getInstance(
                            "AndroidKeyStore"
                    );

            keyStore.load(null);

            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }

        } catch (Exception ignored) {}
    }

    private static String scopeId(String scope) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] bytes =
                    digest.digest(
                            TemplateProtector
                                    .normalizeScope(scope)
                                    .getBytes(
                                            StandardCharsets.UTF_8
                                    )
                    );

            StringBuilder sb =
                    new StringBuilder();

            for (int i = 0; i < 12; i++) {
                sb.append(
                        String.format(
                                "%02x",
                                bytes[i] & 0xFF
                        )
                );
            }

            Arrays.fill(bytes, (byte) 0);
            return sb.toString();

        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SecretKey getOrCreateWrappingKey()
            throws Exception {

        KeyStore keyStore =
                KeyStore.getInstance(
                        "AndroidKeyStore"
                );

        keyStore.load(null);

        SecretKey existing =
                (SecretKey) keyStore.getKey(
                        KEY_ALIAS,
                        null
                );

        if (existing != null) {
            return existing;
        }

        KeyGenerator generator =
                KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore"
                );

        KeyGenParameterSpec spec =
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT |
                                KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(
                                KeyProperties.BLOCK_MODE_GCM
                        )
                        .setEncryptionPaddings(
                                KeyProperties.ENCRYPTION_PADDING_NONE
                        )
                        .setRandomizedEncryptionRequired(true)
                        .build();

        generator.init(spec);
        return generator.generateKey();
    }
}
