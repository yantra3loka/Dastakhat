package com.dastakhat.system;

import com.dastakhat.config.AppProperties;
import com.dastakhat.documents.StorageService;
import com.dastakhat.pki.SealKeyService;
import jakarta.persistence.EntityManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Health and readiness probes. */
@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final EntityManager entityManager;
    private final StorageService storage;
    private final SealKeyService sealKeys;
    private final AppProperties properties;

    public SystemController(EntityManager entityManager, StorageService storage,
                            SealKeyService sealKeys, AppProperties properties) {
        this.entityManager = entityManager;
        this.storage = storage;
        this.sealKeys = sealKeys;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "dastakhat-backend");
        body.put("version", "1.0.0");
        body.put("time", Instant.now().toString());
        return body;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean ok = true;

        try {
            entityManager.createNativeQuery("select 1").getSingleResult();
            checks.put("database", "UP");
        } catch (Exception e) {
            checks.put("database", "DOWN");
            ok = false;
        }
        try {
            checks.put("storage", storage.exists("0".repeat(32)) ? "UP" : "UP");
        } catch (Exception e) {
            checks.put("storage", "DOWN");
            ok = false;
        }
        checks.put("sealKey", sealKeys.isAvailable() ? "UP" : "MISSING (development PKI not generated)");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "UP" : "DOWN");
        body.put("checks", checks);
        body.put("mockEsp", properties.getMockEspBaseUrl());
        body.put("dscAgent", properties.getDscAgentBaseUrl());
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }
}
