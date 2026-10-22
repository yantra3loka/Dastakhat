package com.dastakhat.dsc;

import com.dastakhat.common.RequestContext;
import com.dastakhat.signing.SignerDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Stage 2 - the DSC endpoints of the signer API.
 *
 * <p>The private key never leaves the token: the backend only prepares the PDF signature and
 * embeds the signature that the local agent returns.</p>
 */
@RestController
@RequestMapping("/api/v1/sign")
public class DscController {

    private final DscService service;

    public DscController(DscService service) {
        this.service = service;
    }

    @PostMapping("/{token}/dsc/prepare")
    public SignerDtos.DscPrepareResponse prepare(@PathVariable String token,
                                                  @Valid @RequestBody DscPrepareRequest body,
                                                  HttpServletRequest request) throws IOException {
        return service.prepare(token, body.certificatePem(),
                RequestContext.clientIp(request), RequestContext.userAgent(request));
    }

    @PostMapping("/{token}/dsc/finish")
    public SignerDtos.DscFinishResponse finish(@PathVariable String token,
                                                @Valid @RequestBody DscFinishRequest body,
                                                HttpServletRequest request) throws IOException {
        return service.finish(token, body.sessionId(), body.signatureBase64(),
                RequestContext.clientIp(request), RequestContext.userAgent(request));
    }

    /** The certificate that the signer selected on the token. */
    public record DscPrepareRequest(@jakarta.validation.constraints.NotBlank String certificatePem) { }

    /** The signature that the token produced. */
    public record DscFinishRequest(@jakarta.validation.constraints.NotNull java.util.UUID sessionId,
                                   @jakarta.validation.constraints.NotBlank String signatureBase64) { }
}
