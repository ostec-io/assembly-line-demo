package io.ostec.focusflow.service.entitlement;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

@Service
public class WebhookSignatureValidationService {

    public boolean validateSignature(String provider, String payload, String providedSignature, String sharedSecret) {
        if (!StringUtils.hasText(provider)
            || !StringUtils.hasText(providedSignature)
            || !StringUtils.hasText(sharedSecret)) {
            return false;
        }

        try {
            String expected = hmacSha256Hex(sharedSecret.trim(), payload != null ? payload : "");
            String actual = normalizeProvidedSignature(provider, providedSignature);
            if (!StringUtils.hasText(actual)) {
                return false;
            }
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            return false;
        }
    }

    public void assertValidSignature(String provider, String payload, String providedSignature, String sharedSecret) {
        if (!validateSignature(provider, payload, providedSignature, sharedSecret)) {
            throw new IllegalStateException("Invalid billing webhook signature");
        }
    }

    private String normalizeProvidedSignature(String provider, String signature) {
        String normalizedProvider = provider.trim().toLowerCase(Locale.ROOT);
        String normalizedSignature = signature.trim();

        if ("stripe".equals(normalizedProvider) && normalizedSignature.contains(",")) {
            String[] segments = normalizedSignature.split(",");
            for (String segment : segments) {
                String candidate = segment.trim();
                if (candidate.startsWith("v1=")) {
                    return cleanupHex(candidate.substring("v1=".length()));
                }
            }
            return null;
        }

        if (normalizedSignature.startsWith("sha256=")) {
            return cleanupHex(normalizedSignature.substring("sha256=".length()));
        }

        if (normalizedSignature.contains("=")) {
            String[] parts = normalizedSignature.split("=", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                return cleanupHex(parts[1]);
            }
        }

        return cleanupHex(normalizedSignature);
    }

    private String cleanupHex(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return null;
        }

        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            return null;
        }
        return normalized;
    }

    private String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to validate webhook signature", ex);
        }
    }
}
