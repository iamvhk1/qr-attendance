package com.qrattend.support;

import com.qrattend.mail.EmailSender;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only {@link EmailSender} that records the last email per recipient instead of
 * sending it. Picked up by component scanning in every {@code @SpringBootTest} and marked
 * {@code @Primary}, so integration tests can read the verification code that was "emailed".
 */
@Component
@Primary
public class RecordingEmailSender implements EmailSender {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private final Map<String, String> lastTextByRecipient = new ConcurrentHashMap<>();

    @Override
    public void send(String to, String subject, String textBody, String htmlBody) {
        lastTextByRecipient.put(to, textBody);
    }

    /** The 6-digit code from the last email sent to {@code email}. */
    public String lastCodeFor(String email) {
        String body = lastTextByRecipient.get(email);
        if (body == null) {
            throw new AssertionError("No email was sent to " + email);
        }
        Matcher m = CODE.matcher(body);
        if (!m.find()) {
            throw new AssertionError("No 6-digit code in email to " + email + ": " + body);
        }
        return m.group(1);
    }

    public boolean hasEmailFor(String email) {
        return lastTextByRecipient.containsKey(email);
    }

    public void clear() {
        lastTextByRecipient.clear();
    }
}
