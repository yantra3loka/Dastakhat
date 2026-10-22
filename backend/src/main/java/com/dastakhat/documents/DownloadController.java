package com.dastakhat.documents;

import com.dastakhat.common.RequestContext;
import com.dastakhat.signing.SigningService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The download link that is sent with the completion notification. The token is a bearer
 * secret with its own expiry.
 */
@RestController
@RequestMapping("/api/v1/download")
public class DownloadController {

    private final SigningService signing;

    public DownloadController(SigningService signing) {
        this.signing = signing;
    }

    @GetMapping("/{token}")
    public ResponseEntity<Resource> download(@PathVariable String token, HttpServletRequest request) {
        byte[] content = signing.finalDocumentForDownload(token,
                RequestContext.clientIp(request), RequestContext.userAgent(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dastakhat-signed.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }
}
