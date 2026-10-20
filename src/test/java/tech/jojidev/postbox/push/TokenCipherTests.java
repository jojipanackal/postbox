package tech.jojidev.postbox.push;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenCipherTests {
    @Test
    void encryptsWithRandomNonceAndRequiresConfiguredKey() {
        TokenCipher cipher = new TokenCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String first = cipher.encrypt("ExpoPushToken[secret]");
        String second = cipher.encrypt("ExpoPushToken[secret]");
        assertNotEquals(first, second);
        assertFalse(first.contains("secret"));
        assertEquals("ExpoPushToken[secret]", cipher.decrypt(first));
        assertThrows(TokenCipher.KeyUnavailable.class, () -> new TokenCipher("").encrypt("secret"));
    }
}
