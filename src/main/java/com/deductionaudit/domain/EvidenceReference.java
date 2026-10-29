package com.deductionaudit.domain;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public record EvidenceReference(Kind kind, String key, String qualifier) {

    public enum Kind {
        ASN,
        BOL,
        POD,
        PRICE,
        INVOICE,
        PROMO,
        SETTLE,
        EDI812
    }

    public static final Pattern PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:" + Arrays.stream(Kind.values()).map(Enum::name)
                    .collect(Collectors.joining("|")) + "):[A-Za-z0-9._:\\-·§]+");

    public static EvidenceReference parse(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("malformed evidence id: " + id);
        }
        Kind kind;
        try {
            kind = Kind.valueOf(id.substring(0, colon));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown evidence prefix: " + id);
        }
        String rest = id.substring(colon + 1);
        return switch (kind) {
            case PRICE -> {
                int last = rest.lastIndexOf(':');
                if (last < 0) {
                    throw new IllegalArgumentException("price id needs sku and date: " + id);
                }
                yield new EvidenceReference(kind, rest.substring(0, last), rest.substring(last + 1));
            }
            case PROMO -> {
                int split = rest.indexOf("·§");
                if (split < 0) {
                    throw new IllegalArgumentException("promotion id needs doc and clause: " + id);
                }
                yield new EvidenceReference(kind, rest.substring(0, split), rest.substring(split + 2));
            }
            default -> new EvidenceReference(kind, rest, null);
        };
    }
}
