package com.dastakhat.documents;

import com.dastakhat.common.ApiException;
import com.dastakhat.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The storage must never build a path from a user supplied file name. */
class StorageServiceTest {

    private StorageService storage;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("dastakhat-storage");
        AppProperties properties = new AppProperties();
        properties.setStorageDir(root.toString());
        storage = new StorageService(properties);
    }

    @Test
    void aStoredDocumentCanBeReadBack() {
        byte[] content = "hello dastakhat".getBytes();
        String key = storage.store(content, "pdf");
        assertArrayEquals(content, storage.read(key));
        assertTrue(storage.exists(key));
    }

    @Test
    void theKeyIsRandomAndCarriesNoUserInput() {
        String key = storage.store("x".getBytes(), "pdf");
        assertTrue(key.matches("^[0-9a-f]{32}\\.pdf$"), "unexpected key: " + key);
    }

    @Test
    void pathTraversalIsRejected() {
        assertThrows(ApiException.class, () -> storage.resolve("../../etc/passwd"));
        assertThrows(ApiException.class, () -> storage.resolve("..%2F..%2Fetc%2Fpasswd"));
        assertThrows(ApiException.class, () -> storage.resolve("/etc/passwd"));
        assertThrows(ApiException.class, () -> storage.resolve(""));
        assertThrows(ApiException.class, () -> storage.resolve(null));
    }

    @Test
    void readingAnUnknownKeyFails() {
        assertThrows(ApiException.class, () -> storage.read("0".repeat(32) + ".pdf"));
    }

    @Test
    void theFileNameIsSanitised() {
        assertEquals("report.pdf", StorageService.sanitizeFilename("../../etc/report.pdf"));
        assertEquals("report.pdf", StorageService.sanitizeFilename("C:\\temp\\report.pdf"));
        assertEquals("document.pdf", StorageService.sanitizeFilename(""));
        assertEquals("document.pdf", StorageService.sanitizeFilename(null));
        assertFalse(StorageService.sanitizeFilename("a/b/../c.pdf").contains("/"));
    }

    @Test
    void aDocumentCanBeDeleted() {
        String key = storage.store("x".getBytes(), "pdf");
        storage.delete(key);
        assertFalse(storage.exists(key));
    }
}
