package cn.tan.watchgate;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** API Key 只保存在本机，并使用 Android Keystore 加密。 */
public final class ApiKeyVault {
    private static final String ALIAS = "watchgate_qwen_api_key_v1";
    private static final String PREFS = "watch_gate_qwen_secret";
    private static final String KEY = "encrypted_key";
    private ApiKeyVault() { }

    public static synchronized void save(Context context, String apiKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] encrypted = cipher.doFinal(apiKey.trim().getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
            + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!prefs(context).edit().putString(KEY, value).commit()) throw new IllegalStateException("无法保存 API Key");
    }

    public static synchronized String read(Context context) throws Exception {
        String value = prefs(context).getString(KEY, "");
        if (value.isEmpty()) return "";
        int split = value.indexOf(':');
        if (split < 1) throw new IllegalStateException("API Key 密文格式无效");
        byte[] iv = Base64.decode(value.substring(0, split), Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(value.substring(split + 1), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public static boolean isConfigured(Context context) {
        return !prefs(context).getString(KEY, "").isEmpty();
    }

    public static synchronized void clear(Context context) {
        prefs(context).edit().remove(KEY).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }
}
