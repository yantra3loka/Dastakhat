package com.dastakhat.signing;

import com.dastakhat.common.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public API that the signers use. No account is needed. */
@RestController
@RequestMapping("/api/v1/sign")
public class SignerController {

    private final SigningService signing;

    public SignerController(SigningService signing) {
        this.signing = signing;
    }

    @GetMapping("/{token}")
    public SignerDtos.SignerView view(@PathVariable String token, HttpServletRequest request) {
        return signing.signerView(token, RequestContext.clientIp(request), RequestContext.userAgent(request));
    }

    @GetMapping("/{token}/document")
    public ResponseEntity<Resource> document(@PathVariable String token) {
        byte[] content = signing.document(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"document.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }

    @PostMapping("/{token}/consent")
    public ResponseEntity<Void> consent(@PathVariable String token,
                                        @Valid @RequestBody SignerDtos.ConsentRequest body,
                                        HttpServletRequest request) {
        signing.consent(token, body.consent(), body.consentText(),
                RequestContext.clientIp(request), RequestContext.userAgent(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{token}/complete")
    public SignerDtos.CompleteResponse complete(@PathVariable String token,
                                                 @Valid @RequestBody SignerDtos.CompleteRequest body,
                                                 HttpServletRequest request) {
        return signing.complete(token, body,
                RequestContext.clientIp(request), RequestContext.userAgent(request));
    }

    @PostMapping("/{token}/decline")
    public ResponseEntity<Void> decline(@PathVariable String token,
                                         @RequestBody java.util.Map<String, String> body,
                                         HttpServletRequest request) {
        String reason = body == null ? null : body.get("reason");
        signing.decline(token, reason == null ? "No reason given" : reason,
                RequestContext.clientIp(request), RequestContext.userAgent(request));
        return ResponseEntity.ok().build();
    }
}
