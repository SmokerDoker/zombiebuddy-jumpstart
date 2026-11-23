package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ZBSVerifierTest {

    private static final SteamID64 AUTHOR_ID = new SteamID64(76561198000000000L);
    private static final SteamID64 OTHER_ID = new SteamID64(76561198000000001L);
    private static final WorkshopItemID WORKSHOP_ID = new WorkshopItemID(3709229404L);

    @TempDir
    Path tempDir;

    @Test
    void verify_acceptsValidSignatureFromKnownAuthorKey() throws Exception {
        SignedFixture fixture = signedFixture(AUTHOR_ID);

        ZBSVerifier.Verification result = ZBSVerifier.verify(
            fixture.jarPath,
            fixture.zbsPath,
            fixture.jarHash,
            AUTHOR_ID,
            knownAuthors(AUTHOR_ID, fixture.publicKeyHex)
        );

        assertInstanceOf(ZBSVerifier.ValidSignature.class, result);
        assertEquals(AUTHOR_ID, result.sid);
        assertEquals(List.of(fixture.publicKeyHex), result.profileKeys);
    }

    @Test
    void verify_rejectsUploaderMismatchBeforeCheckingSignature() throws Exception {
        SignedFixture fixture = signedFixture(AUTHOR_ID);

        ZBSVerifier.Verification result = ZBSVerifier.verify(
            fixture.jarPath,
            fixture.zbsPath,
            fixture.jarHash,
            OTHER_ID,
            knownAuthors(AUTHOR_ID, fixture.publicKeyHex)
        );

        assertInstanceOf(ZBSVerifier.InvalidSignature.class, result);
        assertEquals(AUTHOR_ID, result.sid);
        assertEquals("Declared SteamID64 does not match Workshop item uploader.", result.detailedMessage);
    }

    @Test
    void verify_rejectsTamperedJarHash() throws Exception {
        SignedFixture fixture = signedFixture(AUTHOR_ID);

        ZBSVerifier.Verification result = ZBSVerifier.verify(
            fixture.jarPath,
            fixture.zbsPath,
            "0".repeat(64),
            AUTHOR_ID,
            knownAuthors(AUTHOR_ID, fixture.publicKeyHex)
        );

        assertInstanceOf(ZBSVerifier.InvalidSignature.class, result);
        assertEquals("Invalid signature — JAR may have been tampered with.", result.detailedMessage);
    }

    @Test
    void verify_reportsMalformedSidecar() throws Exception {
        Path jarPath = writeJar("malformed jar");
        Path zbsPath = tempDir.resolve("malformed.jar.zbs");
        Files.writeString(zbsPath, "ZBS\nSteamID64:not-a-steamid\nSignature:00\n");

        ZBSVerifier.Verification result = ZBSVerifier.verify(
            jarPath,
            zbsPath,
            sha256Hex(jarPath),
            AUTHOR_ID,
            Map.of()
        );

        assertInstanceOf(ZBSVerifier.InvalidSignature.class, result);
        assertNull(result.sid);
        assertTrue(result.detailedMessage.contains("Second line must be SteamID64"));
    }

    @Test
    void verify_reportsMissingSidecar() throws Exception {
        Path jarPath = writeJar("missing zbs");
        Path missingZBS = tempDir.resolve("missing.jar.zbs");

        ZBSVerifier.Verification result = ZBSVerifier.verify(jarPath, missingZBS, sha256Hex(jarPath));

        assertInstanceOf(ZBSVerifier.MissingSignature.class, result);
        assertNull(result.sid);
        assertTrue(result.detailedMessage.contains("Missing .zbs file next to JAR"));
    }

    @Test
    void check_treatsMissingSidecarAsUnsignedWhenAllowed() throws Exception {
        Path jarPath = writeJar("unsigned allowed");
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetails = workshopDetails(AUTHOR_ID);

        ZBSVerifier.CheckResult result = ZBSVerifier.check(
            jarPath,
            sha256Hex(jarPath),
            WORKSHOP_ID,
            true,
            true,
            workshopDetails
        );

        assertFalse(result.flags().has(MF_SIGNED));
        assertEquals(AUTHOR_ID, result.uploaderID());
        assertTrue(result.flags().has(MF_VALID));
        assertNull(result.verification());
    }

    @Test
    void check_blocksMissingSidecarWhenUnsignedNotAllowed() throws Exception {
        Path jarPath = writeJar("unsigned blocked");
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetails = workshopDetails(AUTHOR_ID);

        ZBSVerifier.CheckResult result = ZBSVerifier.check(
            jarPath,
            sha256Hex(jarPath),
            WORKSHOP_ID,
            true,
            false,
            workshopDetails
        );

        assertFalse(result.flags().has(MF_SIGNED));
        assertEquals(AUTHOR_ID, result.uploaderID());
        assertFalse(result.flags().has(MF_VALID));
        assertEquals("missing .zbs file; allow_unsigned_mods=false", result.blockReason());
    }

    @Test
    void check_bindsSignatureToWorkshopUploader() throws Exception {
        SignedFixture fixture = signedFixture(AUTHOR_ID);
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetails = workshopDetails(AUTHOR_ID);

        ZBSVerifier.CheckResult result = ZBSVerifier.check(
            fixture.jarPath,
            fixture.jarHash,
            WORKSHOP_ID,
            true,
            true,
            workshopDetails,
            knownAuthors(AUTHOR_ID, fixture.publicKeyHex)
        );

        assertTrue(result.flags().hasAll(MF_VALID, MF_SIGNED));
        assertEquals(AUTHOR_ID, result.uploaderID());
        assertInstanceOf(ZBSVerifier.ValidSignature.class, result.verification());
    }

    @Test
    void noticeForUi_combinesShortAndDetailedMessages() {
        ZBSVerifier.Verification result = new ZBSVerifier.InvalidSignature(AUTHOR_ID, "Long explanation.");

        assertEquals("Invalid signature.\nLong explanation.", ZBSVerifier.noticeForUi(result));
    }

    // RFC 8032, section 7.1, TEST 2 (one-byte message 0x72)
    private static final String RFC8032_PUBLIC_KEY = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String RFC8032_SIGNATURE =
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
        "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00";

    @Test
    void verifyEd25519_acceptsRfc8032TestVector() throws Exception {
        HexFormat hex = HexFormat.of();
        assertTrue(ZBSVerifier.verifyEd25519(hex.parseHex(RFC8032_PUBLIC_KEY), new byte[] { 0x72 }, hex.parseHex(RFC8032_SIGNATURE)));
    }

    @Test
    void verifyEd25519_rejectsChangedMessageOrSignature() throws Exception {
        HexFormat hex = HexFormat.of();
        byte[] pub = hex.parseHex(RFC8032_PUBLIC_KEY);
        byte[] sig = hex.parseHex(RFC8032_SIGNATURE);
        assertFalse(ZBSVerifier.verifyEd25519(pub, new byte[] { 0x73 }, sig));
        sig[0] ^= 1;
        assertFalse(ZBSVerifier.verifyEd25519(pub, new byte[] { 0x72 }, sig));
    }

    @Test
    void verifyEd25519_undecodableKeyOrSignatureDoesNotMatch() throws Exception {
        HexFormat hex = HexFormat.of();
        byte[] pub = hex.parseHex(RFC8032_PUBLIC_KEY);
        byte[] sig = hex.parseHex(RFC8032_SIGNATURE);
        byte[] msg = { 0x72 };
        assertFalse(ZBSVerifier.verifyEd25519(pub, msg, new byte[63]));
        assertFalse(ZBSVerifier.verifyEd25519(new byte[31], msg, sig));
        byte[] notOnCurve = new byte[32];
        notOnCurve[0] = 2; // y = 2 has no x on the curve
        assertFalse(ZBSVerifier.verifyEd25519(notOnCurve, msg, sig));
        byte[] sNotReduced = sig.clone();
        Arrays.fill(sNotReduced, 32, 64, (byte) 0xff); // S >= group order
        assertFalse(ZBSVerifier.verifyEd25519(pub, msg, sNotReduced));
    }

    @Test
    void verify_acceptsSignatureFromJdkSigner() throws Exception {
        // the other direction from signedFixture (Bouncy Castle signs, the JDK verifies)
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = keys.getPublic().getEncoded();
        String publicKeyHex = hex(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
        Path jarPath = writeJar("jdk signed");
        String jarHash = sha256Hex(jarPath);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(("ZBS:" + AUTHOR_ID.value() + ":" + jarHash).getBytes(StandardCharsets.UTF_8));
        Path zbsPath = jarPath.resolveSibling(jarPath.getFileName() + ".zbs");
        Files.writeString(zbsPath, "ZBS\nSteamID64:" + AUTHOR_ID.value() + "\nSignature:" + hex(signer.sign()) + "\n");

        ZBSVerifier.Verification result = ZBSVerifier.verify(
            jarPath, zbsPath, jarHash, AUTHOR_ID, knownAuthors(AUTHOR_ID, publicKeyHex));

        assertInstanceOf(ZBSVerifier.ValidSignature.class, result);
    }

    private SignedFixture signedFixture(SteamID64 sid) throws Exception {
        Path jarPath = writeJar("jar contents for " + sid);
        String jarHash = sha256Hex(jarPath);

        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i + 1);
        }
        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(seed, 0);
        String publicKeyHex = hex(privateKey.generatePublicKey().getEncoded());

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        byte[] message = ("ZBS:" + sid.value() + ":" + jarHash).getBytes(StandardCharsets.UTF_8);
        signer.update(message, 0, message.length);
        String signatureHex = hex(signer.generateSignature());

        Path zbsPath = jarPath.resolveSibling(jarPath.getFileName().toString() + ".zbs");
        Files.writeString(
            zbsPath,
            "ZBS\nSteamID64:" + sid.value() + "\nSignature:" + signatureHex + "\n",
            StandardCharsets.UTF_8
        );

        return new SignedFixture(jarPath, zbsPath, jarHash, publicKeyHex);
    }

    private Path writeJar(String content) throws Exception {
        Path jar = tempDir.resolve("test-" + Math.abs(content.hashCode()) + ".jar");
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        return jar;
    }

    private static Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthors(SteamID64 sid, String publicKeyHex) {
        KnownAuthors.AuthorEntry author = new KnownAuthors.AuthorEntry();
        author.id = sid;
        author.name = "Test Author";
        author.keys = List.of(publicKeyHex);
        Map<SteamID64, KnownAuthors.AuthorEntry> authors = new LinkedHashMap<>();
        authors.put(sid, author);
        return authors;
    }

    private static Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetails(SteamID64 uploaderID) {
        return Map.of(
            WORKSHOP_ID,
            new SteamWorkshop.ItemDetails(
                new SteamWorkshop.BanInfo(false, ""),
                uploaderID
            )
        );
    }

    private static String sha256Hex(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private record SignedFixture(Path jarPath, Path zbsPath, String jarHash, String publicKeyHex) {}
}
