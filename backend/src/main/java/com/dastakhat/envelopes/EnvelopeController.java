package com.dastakhat.envelopes;

import com.dastakhat.common.RequestContext;
import com.dastakhat.documents.Document;
import com.dastakhat.documents.DocumentKind;
import com.dastakhat.documents.StorageService;
import com.dastakhat.fields.Field;
import com.dastakhat.recipients.Recipient;
import com.dastakhat.security.CurrentUser;
import com.dastakhat.signing.Signature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The sender facing REST API. */
@RestController
@RequestMapping("/api/v1/envelopes")
public class EnvelopeController {

    private final EnvelopeService envelopes;
    private final EnvelopeMapper mapper;
    private final StorageService storage;
    private final CurrentUser currentUser;

    public EnvelopeController(EnvelopeService envelopes, EnvelopeMapper mapper,
                              StorageService storage, CurrentUser currentUser) {
        this.envelopes = envelopes;
        this.mapper = mapper;
        this.storage = storage;
        this.currentUser = currentUser;
    }

    @GetMapping
    public EnvelopeDtos.EnvelopePage list(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "25") int size) {
        UUID ownerId = currentUser.requireId();
        EnvelopeStatus envelopeStatus = null;
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            try {
                envelopeStatus = EnvelopeStatus.fromValue(status);
            } catch (IllegalArgumentException e) {
                throw new com.dastakhat.common.UnprocessableException("Unknown status filter: " + status);
            }
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<Envelope> result = envelopes.list(ownerId, envelopeStatus,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<EnvelopeDtos.EnvelopeView> content = result.getContent().stream()
                .map(envelope -> view(envelope))
                .toList();
        return new EnvelopeDtos.EnvelopePage(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/stats")
    public Map<String, Integer> stats() {
        return envelopes.stats(currentUser.requireId());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EnvelopeDtos.EnvelopeView> create(@RequestParam("file") MultipartFile file,
                                                            @RequestParam(required = false) String title,
                                                            HttpServletRequest request) throws IOException {
        UUID ownerId = currentUser.requireId();
        Envelope envelope = envelopes.createDraft(ownerId, file, title,
                currentUser.requireId().toString(),
                RequestContext.clientIp(request), RequestContext.userAgent(request));
        return ResponseEntity.status(201).body(view(envelope));
    }

    @GetMapping("/{envelopeId}")
    public EnvelopeDtos.EnvelopeView get(@PathVariable UUID envelopeId) {
        return view(envelopes.requireOwned(currentUser.requireId(), envelopeId));
    }

    @PatchMapping("/{envelopeId}")
    public EnvelopeDtos.EnvelopeView update(@PathVariable UUID envelopeId,
                                            @Valid @RequestBody EnvelopeDtos.EnvelopeUpdateRequest body,
                                            HttpServletRequest request) {
        UUID ownerId = currentUser.requireId();
        Envelope envelope = envelopes.update(ownerId, envelopeId, body, ownerId.toString(),
                RequestContext.clientIp(request), RequestContext.userAgent(request));
        return view(envelope);
    }

    @PutMapping("/{envelopeId}/recipients")
    public EnvelopeDtos.EnvelopeView replaceRecipients(@PathVariable UUID envelopeId,
                                                       @Valid @RequestBody EnvelopeDtos.RecipientsRequest body,
                                                       HttpServletRequest request) {
        UUID ownerId = currentUser.requireId();
        Envelope envelope = envelopes.replaceRecipients(ownerId, envelopeId, body.recipients(),
                ownerId.toString(), RequestContext.clientIp(request), RequestContext.userAgent(request));
        return view(envelope);
    }

    @PutMapping("/{envelopeId}/fields")
    public EnvelopeDtos.EnvelopeView replaceFields(@PathVariable UUID envelopeId,
                                                  @Valid @RequestBody EnvelopeDtos.FieldsRequest body,
                                                  HttpServletRequest request) {
        UUID ownerId = currentUser.requireId();
        Envelope envelope = envelopes.replaceFields(ownerId, envelopeId, body.fields(),
                ownerId.toString(), RequestContext.clientIp(request), RequestContext.userAgent(request));
        return view(envelope);
    }

    @PostMapping("/{envelopeId}/send")
    public ResponseEntity<EnvelopeDtos.EnvelopeView> send(@PathVariable UUID envelopeId,
                                                           HttpServletRequest request) {
        UUID ownerId = currentUser.requireId();
        Envelope envelope = envelopes.send(ownerId, envelopeId, ownerId.toString(),
                RequestContext.clientIp(request), RequestContext.userAgent(request));
        return ResponseEntity.accepted().body(view(envelope));
    }

    @PostMapping("/{envelopeId}/void")
    public EnvelopeDtos.EnvelopeView voidEnvelope(@PathVariable UUID envelopeId,
                                                  @RequestParam(required = false) String reason,
                                                  HttpServletRequest request) {
        UUID ownerId = currentUser.requireId();
        Envelope envelope = envelopes.voidEnvelope(ownerId, envelopeId, reason, ownerId.toString(),
                RequestContext.clientIp(request), RequestContext.userAgent(request));
        return view(envelope);
    }

    @GetMapping("/{envelopeId}/documents/{kind}")
    public ResponseEntity<Resource> download(@PathVariable UUID envelopeId, @PathVariable String kind) {
        UUID ownerId = currentUser.requireId();
        DocumentKind documentKind;
        try {
            documentKind = DocumentKind.fromValue(kind);
        } catch (IllegalArgumentException e) {
            throw new com.dastakhat.common.NotFoundException("Unknown document kind.");
        }
        Document document = envelopes.requireDocument(ownerId, envelopeId, documentKind);
        byte[] content = storage.read(document.getStorageKey());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + document.getFilename().replace("\"", "") + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }

    private EnvelopeDtos.EnvelopeView view(Envelope envelope) {
        List<Recipient> recipients = envelopes.recipientsOf(envelope.getId());
        List<Field> fields = envelopes.fieldsOf(envelope.getId());
        Map<UUID, Signature> signatures = envelopes.signaturesOf(envelope.getId());
        return mapper.toView(envelope, recipients, fields, signatures);
    }
}
