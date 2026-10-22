package com.dastakhat.signing;

import com.dastakhat.common.Hashing;
import com.dastakhat.envelopes.Envelope;
import com.dastakhat.recipients.Recipient;
import com.dastakhat.recipients.RecipientRepository;
import com.dastakhat.recipients.RecipientStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Issues the signing tokens.
 *
 * <p>Only the SHA-256 of a token is stored. The raw value exists exactly once, in the link
 * that is handed to the recipient.</p>
 */
@Service
public class TokenService {

    private final RecipientRepository recipients;

    public TokenService(RecipientRepository recipients) {
        this.recipients = recipients;
    }

    /**
     * Creates a new token for the recipient and returns the raw value. Any previous token of the
     * recipient becomes invalid.
     */
    @Transactional
    public String issue(Recipient recipient, Envelope envelope) {
        String rawToken = Hashing.newToken();
        // A token that was issued before becomes invalid.
        if (recipient.getTokenHash() != null) {
            recipient.setUsedTokenHash(recipient.getTokenHash());
        }
        recipient.setTokenHash(Hashing.sha256Hex(rawToken));
        recipient.setTokenExpiresAt(envelope.getExpiresAt());
        if (recipient.getStatus() == RecipientStatus.PENDING) {
            recipient.setStatus(RecipientStatus.NOTIFIED);
        }
        recipient.setNotifiedAt(Instant.now());
        recipients.save(recipient);
        return rawToken;
    }

    /** Invalidates the token of a recipient. */
    @Transactional
    public void invalidate(Recipient recipient) {
        if (recipient.getTokenHash() != null) {
            recipient.setUsedTokenHash(recipient.getTokenHash());
        }
        recipient.setTokenHash(null);
        recipient.setTokenExpiresAt(null);
        recipients.save(recipient);
    }
}
