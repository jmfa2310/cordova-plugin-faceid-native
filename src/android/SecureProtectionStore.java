package com.company.faceidnative;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Persists the template-protection master key encrypted by a non-exportable
 * Android Keystore AES key. No biometric template or raw FaceNet embedding is
 * stored here.
 */
public final class SecureProtectionStore {

    private static final String PREFS = "FaceIDNativeSecure";
    private static final String P_CIPHER = "protection_key_cipher";
    private static final String P_IV = "protection_key_iv";
    private static final String P_VERSION = "template_version";
    private static final String KEY_ALIAS = "FaceIDNative.TemplateKeyWrap.v1";

    private SecureProtectionStore() {}

    public static final class SavedProtection {
        public final byte[] masterKey;
        public final int version;

        SavedProtection(byte[] masterKey, int version) {
            this.masterKey = masterKey;
            this.version = version;
        }
    }

    public static boolean exists(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.contains(P_CIPHER) && prefs.contains(P_IV) && prefs.contains(P_VERSION);
    }

    public static void save(Context context, byte[] masterKey, int version) throws Exception {
        if (masterKey == null || masterKey.length < 32) {
            throw new IllegalArgumentException("Invalid protection key.");
        }

        SecretKey wrappingKey = getOrCreateWrappingKey();
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(masterKey);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean ok = prefs.edit()
                .putString(P_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(P_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putInt(P_VERSION, version)
                .commit();

        Arrays.fill(encrypted, (byte) 0);
        Arrays.fill(iv, (byte) 0);

        if (!ok) {
            throw new IllegalStateException("Could not persist protection configuration.");
        }
    }

    public static SavedProtection load(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encryptedText = prefs.getString(P_CIPHER, null);
        String ivText = prefs.getString(P_IV, null);
        int version = prefs.getInt(P_VERSION, 0);

        if (encryptedText == null || ivText == null || version < 1) {
            return null;
        }

        byte[] encrypted = Base64.decode(encryptedText, Base64.NO_WRAP);
        byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);

        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            SecretKey wrappingKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (wrappingKey == null) {
                return null;
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(128, iv));
            byte[] masterKey = cipher.doFinal(encrypted);
            return new SavedProtection(masterKey, version);
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static void clear(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        } catch (Exception ignored) {}

        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception ignored) {}
    }

    private static SecretKey getOrCreateWrappingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            return existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();

        generator.init(spec);
        return generator.generateKey();
    }
}
