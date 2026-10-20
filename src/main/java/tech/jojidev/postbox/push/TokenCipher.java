package tech.jojidev.postbox.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class TokenCipher {
    public static class KeyUnavailable extends RuntimeException {
        public KeyUnavailable() { super("Push encryption key unavailable"); }
    }
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(@Value("${postbox.push.encryption-key:}") String configured) {
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(configured); }
        catch (IllegalArgumentException exception) { decoded = new byte[0]; }
        key = decoded.length == 32 ? decoded : null;
    }

    public String encrypt(String token) {
        if (key == null) throw new KeyUnavailable();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) { throw new IllegalStateException("Unable to encrypt device token", exception); }
    }

    public String decrypt(String encoded) {
        if (key == null) throw new KeyUnavailable();
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new KeyUnavailable(); }
    }
}
