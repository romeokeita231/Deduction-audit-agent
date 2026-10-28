package com.deductionaudit.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class EvidenceReferenceTest {

    static Stream<Arguments> ids() {
        return Stream.of(
                arguments("ASN:SHP-1", EvidenceReference.Kind.ASN, "SHP-1", null),
                arguments("BOL:SHP-1", EvidenceReference.Kind.BOL, "SHP-1", null),
                arguments("POD:SHP-1", EvidenceReference.Kind.POD, "SHP-1", null),
                arguments("PRICE:SKU-1:2026-01-01", EvidenceReference.Kind.PRICE, "SKU-1", "2026-01-01"),
                arguments("PRICE:SKU:WITH:COLON:2026-01-01", EvidenceReference.Kind.PRICE,
                        "SKU:WITH:COLON", "2026-01-01"),
                arguments("INVOICE:INV-1", EvidenceReference.Kind.INVOICE, "INV-1", null),
                arguments("PROMO:PA-07·§3.2", EvidenceReference.Kind.PROMO, "PA-07", "3.2"),
                arguments("SETTLE:PA-07", EvidenceReference.Kind.SETTLE, "PA-07", null),
                arguments("EDI812:11111111-1111-1111-1111-111111111111", EvidenceReference.Kind.EDI812,
                        "11111111-1111-1111-1111-111111111111", null));
    }

    @ParameterizedTest
    @MethodSource("ids")
    void parsesEveryPrefixIntoNaturalKeyParts(String id, EvidenceReference.Kind kind, String key,
                                              String qualifier) {
        EvidenceReference reference = EvidenceReference.parse(id);

        assertThat(reference.kind()).isEqualTo(kind);
        assertThat(reference.key()).isEqualTo(key);
        assertThat(reference.qualifier()).isEqualTo(qualifier);
    }

    @Test
    void thePatternMatchesEveryPrefixInTheClosedSet() {
        for (EvidenceReference.Kind kind : EvidenceReference.Kind.values()) {
            assertThat(EvidenceReference.PATTERN.matcher(kind.name() + ":X-1").find()).isTrue();
        }
        assertThat(EvidenceReference.PATTERN.matcher("See POD:SHIP-0442, please.").find()).isTrue();
        assertThat(EvidenceReference.PATTERN.matcher("pod:SHIP-0442").find()).isFalse();
    }

    @Test
    void unknownPrefixesAreRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EvidenceReference.parse("XYZ:1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
