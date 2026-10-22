package com.dastakhat.esignmock;

import com.dastakhat.common.RequestContext;
import com.dastakhat.signing.SignerDtos;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * The MOCK eSign API.
 *
 * <p><b>Everything in this controller is a simulation.</b> No real Aadhaar authentication and
 * no real eSign provider is involved.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class EsignMockController {

    private final EsignMockService service;

    public EsignMockController(EsignMockService service) {
        this.service = service;
    }

    @PostMapping("/sign/{token}/esign/start")
    public SignerDtos.EsignStartResponse start(@PathVariable String token, HttpServletRequest request)
            throws java.io.IOException {
        return service.start(token, RequestContext.clientIp(request), RequestContext.userAgent(request));
    }

    /** The callback of the MOCK provider. It accepts the raw XML of the response. */
    @PostMapping(value = "/esign/callback", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public SignerDtos.EsignCallbackResponse callback(@RequestBody byte[] xml, HttpServletRequest request)
            throws Exception {
        return service.callback(xml, RequestContext.clientIp(request), RequestContext.userAgent(request));
    }

    /** The callback also accepts the XML inside a JSON envelope (used by the SPA and the tests). */
    @PostMapping(value = "/esign/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SignerDtos.EsignCallbackResponse callbackJson(@RequestBody java.util.Map<String, String> body,
                                                         HttpServletRequest request) throws Exception {
        String xml = body == null ? null : body.get("responseXml");
        if (xml == null) {
            throw new com.dastakhat.common.UnprocessableException("The response XML is missing.");
        }
        return service.callback(xml.getBytes(StandardCharsets.UTF_8),
                RequestContext.clientIp(request), RequestContext.userAgent(request));
    }
}
