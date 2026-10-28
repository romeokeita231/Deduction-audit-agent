package com.deductionaudit.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class PolicyProvider {

    private final String text;
    private final String version;

    public PolicyProvider() {
        try (var stream = new ClassPathResource("policy/audit-policy.md").getInputStream()) {
            this.text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("policy document is missing", e);
        }
        this.version = sha256(text).substring(0, 12);
    }

    public String text() {
        return text;
    }

    public String version() {
        return version;
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
