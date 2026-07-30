package com.leantpm.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@CapacitorPlugin(name = "SecureVault")
public class SecureVaultPlugin extends Plugin {
    private static final String KEY_ALIAS = "leantpm-mobile-vault-v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String PREFERENCES = "leantpm_secure_vault";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    @PluginMethod
    public void get(PluginCall call) {
        String key = requiredKey(call);
        if (key == null) {
            return;
        }
        try {
            String encrypted = preferences().getString(key, null);
            JSObject result = new JSObject();
            result.put("value", encrypted == null ? null : decrypt(encrypted));
            call.resolve(result);
        } catch (Exception exception) {
            call.reject("安全数据读取失败", "SECURE_VAULT_READ_FAILED", exception);
        }
    }

    @PluginMethod
    public void set(PluginCall call) {
        String key = requiredKey(call);
        String value = call.getString("value");
        if (key == null) {
            return;
        }
        if (value == null) {
            call.reject("value 不能为空", "SECURE_VAULT_VALUE_REQUIRED");
            return;
        }
        try {
            preferences().edit().putString(key, encrypt(value)).apply();
            call.resolve();
        } catch (Exception exception) {
            call.reject("安全数据写入失败", "SECURE_VAULT_WRITE_FAILED", exception);
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String key = requiredKey(call);
        if (key == null) {
            return;
        }
        preferences().edit().remove(key).apply();
        call.resolve();
    }

    private String requiredKey(PluginCall call) {
        String key = call.getString("key");
        if (key == null || key.isBlank() || key.length() > 160) {
            call.reject("key 格式不正确", "SECURE_VAULT_KEY_INVALID");
            return null;
        }
        return key;
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + iv.length + ciphertext.length);
        buffer.putInt(iv.length);
        buffer.put(iv);
        buffer.put(ciphertext);
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
        int ivLength = buffer.getInt();
        if (ivLength < 12 || ivLength > 16 || buffer.remaining() <= ivLength) {
            throw new IllegalArgumentException("加密数据格式不正确");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                new GCMParameterSpec(GCM_TAG_BITS, iv)
        );
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
