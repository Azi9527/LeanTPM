package com.leantpm.opscontrol.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class HashedOperatorTokenAuthenticator implements OperatorTokenAuthenticator {

    private static final Pattern ACTOR = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9@._-]{1,127}$");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

    private final Map<String, byte[]> operators;

    public HashedOperatorTokenAuthenticator(Map<String, String> operatorTokenSha256) {
        if (operatorTokenSha256 == null || operatorTokenSha256.isEmpty()
            || operatorTokenSha256.size() > 32) {
            throw new IllegalArgumentException("One to 32 operator token digests are required");
        }
        Map<String, byte[]> validated = new LinkedHashMap<>();
        operatorTokenSha256.forEach((actor, digest) -> {
            if (actor == null || !ACTOR.matcher(actor).matches()) {
                throw new IllegalArgumentException("Operator identity is invalid");
            }
            String normalized = digest == null ? "" : digest.toLowerCase(java.util.Locale.ROOT);
            if (!SHA256.matcher(normalized).matches()) {
                throw new IllegalArgumentException("Operator token digest is invalid");
            }
            validated.put(actor, HexFormat.of().parseHex(normalized));
        });
        operators = Map.copyOf(validated);
    }

    public static HashedOperatorTokenAuthenticator fromPlaintextForTests(
        Map<String, String> plaintextTokens
    ) {
        Map<String, String> digests = new LinkedHashMap<>();
        plaintextTokens.forEach((actor, token) -> digests.put(actor, digest(token)));
        return new HashedOperatorTokenAuthenticator(digests);
    }

    @Override
    public Optional<String> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank() || bearerToken.length() > 4096) {
            return Optional.empty();
        }
        byte[] candidate = sha256(bearerToken.getBytes(StandardCharsets.UTF_8));
        String matched = null;
        for (Map.Entry<String, byte[]> operator : operators.entrySet()) {
            if (MessageDigest.isEqual(candidate, operator.getValue())) {
                matched = operator.getKey();
            }
        }
        java.util.Arrays.fill(candidate, (byte) 0);
        return Optional.ofNullable(matched);
    }

    private static String digest(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Plaintext test token is required");
        }
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
