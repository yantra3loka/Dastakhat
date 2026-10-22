package com.dastakhat.support;

import com.dastakhat.devpki.DevPkiTool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the TEST PKI for the integration tests.
 *
 * <p>The certificates are generated once per test run and are reused by every test class so
 * that the cached Spring context always sees the same seal key.</p>
 */
public final class TestPki {

    /** The directory the test certificates are written to. */
    public static final Path DIRECTORY = Path.of("target", "test-pki").toAbsolutePath().normalize();

    private static boolean generated;

    private TestPki() {
    }

    /** Generates the test certificates if they do not exist yet. */
    public static synchronized void ensure() {
        if (generated) {
            return;
        }
        try {
            Files.createDirectories(DIRECTORY);
            Path seal = DIRECTORY.resolve("platform-seal.p12");
            if (!Files.exists(seal)) {
                DevPkiTool.generateAll(DIRECTORY);
            }
            generated = true;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create the test PKI", e);
        }
    }
}
