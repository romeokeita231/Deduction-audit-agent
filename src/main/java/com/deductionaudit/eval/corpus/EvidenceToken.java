package com.deductionaudit.eval.corpus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

public final class EvidenceToken {

    private static final String PRICE_PREFIX = "PRICE:";
    private static final char PRICE_OFFSET = '@';

    private EvidenceToken() {
    }

    public static String price(String sku, int validFromDaysAgo) {
        return PRICE_PREFIX + sku + ":@" + validFromDaysAgo;
    }

    public static PriceOffset parsePrice(String token) {
        if (!token.startsWith(PRICE_PREFIX)) {
            return null;
        }
        int at = token.lastIndexOf(PRICE_OFFSET);
        if (at < 0) {
            return null;
        }
        return new PriceOffset(token.substring(PRICE_PREFIX.length(), at - 1),
                Integer.parseInt(token.substring(at + 1)));
    }

    public static String resolve(String token, Clock clock) {
        if (!token.startsWith(PRICE_PREFIX)) {
            return token;
        }
        int at = token.lastIndexOf(PRICE_OFFSET);
        if (at < 0) {
            return token;
        }
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        int daysAgo = Integer.parseInt(token.substring(at + 1));
        return token.substring(0, at) + today.minusDays(daysAgo);
    }

    public record PriceOffset(String sku, int daysAgo) {

        public String token() {
            return price(sku, daysAgo);
        }
    }
}
