package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The authors.json in the repository is signed with the real key in {@link KnownAuthors}. */
class KnownAuthorsTest {

    private static String signedAuthorsJson() throws Exception {
        Path p = Path.of("../authors.json");
        if (!Files.isRegularFile(p)) {
            p = Path.of("authors.json");
        }
        // signed with LF line endings; a checkout with core.autocrlf may have CRLF
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void verifySignature_acceptsTheSignedAuthorsJson() throws Exception {
        assertTrue(KnownAuthors.verifySignature(signedAuthorsJson()));
    }

    @Test
    void verifySignature_rejectsChangedContent() throws Exception {
        String body = signedAuthorsJson();
        // someone slips their own key into the first author's entry
        int i = body.indexOf("\"keys\":[\"") + "\"keys\":[\"".length();
        assertTrue(i > "\"keys\":[\"".length());
        String tampered = body.substring(0, i) + flipHexDigit(body.charAt(i)) + body.substring(i + 1);
        assertFalse(KnownAuthors.verifySignature(tampered));
    }

    @Test
    void verifySignature_rejectsChangedSignature() throws Exception {
        String body = signedAuthorsJson();
        int i = body.indexOf("\"signature\": \"") + "\"signature\": \"".length();
        assertTrue(i > "\"signature\": \"".length());
        String tampered = body.substring(0, i) + flipHexDigit(body.charAt(i)) + body.substring(i + 1);
        assertFalse(KnownAuthors.verifySignature(tampered));
    }

    private static char flipHexDigit(char c) {
        return c == '0' ? '1' : '0';
    }
}
