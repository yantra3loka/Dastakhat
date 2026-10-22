package com.dastakhat.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the signing links from Mailpit.
 *
 * <p>The raw signing token is never stored in the database, so the only way to get it is from
 * the e-mail that was sent. The end to end tests therefore use the Mailpit REST API, exactly
 * like the Playwright tests do.</p>
 */
public final class Mailpit {

    private static final String BASE = System.getProperty("mailpit.api", "http://localhost:8025");
    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"<>]+/sign/([A-Za-z0-9_-]+)");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Mailpit() {
    }

    /** The raw signing token of the most recent mail to the given address. */
    public static String latestSigningToken(String email) throws Exception {
        String body = get(BASE + "/api/v1/messages?limit=50");
        JsonNode messages = MAPPER.readTree(body).get("messages");
        String messageId = null;
        for (JsonNode message : messages) {
            for (JsonNode to : message.get("To")) {
                if (email.equalsIgnoreCase(to.get("Address").asText())) {
                    messageId = message.get("ID").asText();
                    break;
                }
            }
            if (messageId != null) {
                break;
            }
        }
        if (messageId == null) {
            throw new IllegalStateException("No mail was sent to " + email);
        }
        JsonNode full = MAPPER.readTree(get(BASE + "/api/v1/message/" + messageId));
        String text = full.hasNonNull("Text") ? full.get("Text").asText() : "";
        if (text.isBlank() && full.hasNonNull("HTML")) {
            text = full.get("HTML").asText();
        }
        Matcher matcher = LINK.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("The mail to " + email + " does not contain a signing link");
        }
        return matcher.group(1);
    }

    /** The number of mails that were sent to the given address. */
    public static int count(String email) throws Exception {
        JsonNode messages = MAPPER.readTree(get(BASE + "/api/v1/messages?limit=50")).get("messages");
        int count = 0;
        for (JsonNode message : messages) {
            for (JsonNode to : message.get("To")) {
                if (email.equalsIgnoreCase(to.get("Address").asText())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Mailpit answered with " + response.statusCode()
                    + " for " + url);
        }
        return response.body();
    }
}
