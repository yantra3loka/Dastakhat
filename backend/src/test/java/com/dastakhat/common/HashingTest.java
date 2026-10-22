package com.dastakhat.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashingTest {

    @Test
    void sha256MatchesTheKnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Hashing.sha256Hex(new byte[0]));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Hashing.sha256Hex("abc"));
    }

    @Test
    void tokensAreUniqueAndUrlSafe() {
        String first = Hashing.newToken();
        String second = Hashing.newToken();
        assertNotEquals(first, second);
        assertTrue(first.matches("^[A-Za-z0-9_-]{40,}$"), "token must be url safe: " + first);
    }

    @Test
    void constantTimeEqualsBehavesLikeEquals() {
        assertTrue(Hashing.constantTimeEquals("abc", "abc"));
        assertFalse(Hashing.constantTimeEquals("abc", "abd"));
        assertFalse(Hashing.constantTimeEquals(null, "abc"));
        assertFalse(Hashing.constantTimeEquals("abc", null));
    }

    @Test
    void hexRoundTrip() {
        byte[] data = new byte[]{0, 1, 15, 16, (byte) 200, (byte) 255};
        assertEquals("00010f10c8ff", Hashing.toHex(data));
        assertEquals("00010f10c8ff", Hashing.toHex(Hashing.hexToBytes("00010F10C8FF")));
    }
}
