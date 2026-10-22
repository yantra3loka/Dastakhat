package com.dastakhat.mail;

import com.dastakhat.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Sends the signing links and the completion notifications.
 *
 * <p>In development the SMTP server is Mailpit, so nothing ever leaves the machine. The raw
 * signing token is only ever written into the body of the e-mail - never into a log.</p>
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    public MailService(JavaMailSender mailSender, AppProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** Sends the signing link to a recipient. */
    public void sendSigningLink(String to, String recipientName, String senderName, String envelopeTitle,
                                String link, Instant expiresAt) {
        String body = """
                Hello %s,

                %s asked you to sign the document "%s".

                Open the following secure link to sign:

                %s

                The link is personal and expires on %s. It can only be used once.
                If you did not expect this e-mail you can safely ignore it.

                --
                Dastakhat
                This is an automated message from a development installation.
                Electronic signatures captured as an image are not cryptographic digital signatures.
                """.formatted(recipientName == null ? "there" : recipientName,
                senderName == null ? "A sender" : senderName,
                envelopeTitle == null ? "a document" : envelopeTitle,
                link,
                expiresAt);
        send(to, "Signature requested: " + envelopeTitle, body);
    }

    /** Sends the completion notification with the download link. */
    public void sendCompletion(String to, String recipientName, String envelopeTitle, String downloadLink) {
        String body = """
                Hello %s,

                The document "%s" has been signed by all parties and is now complete.
                You can download the final, sealed PDF here:

                %s

                --
                Dastakhat
                This is an automated message from a development installation.
                """.formatted(recipientName == null ? "there" : recipientName,
                envelopeTitle == null ? "a document" : envelopeTitle,
                downloadLink);
        send(to, "Completed: " + envelopeTitle, body);
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getMailFromAddress());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Sent '{}' to {}", subject, to);
        } catch (MailException e) {
            // A failing mail server must not break the workflow: the sender can always resend.
            log.error("Could not send the e-mail to {}", to, e);
        }
    }
}
