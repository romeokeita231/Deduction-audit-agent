package com.deductionaudit.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClosedSetTest {

    private static final String EDI = "EDI812:11111111-1111-1111-1111-111111111111";

    @Test
    void evidenceMustComeFromTheToolsThatWereCalled() {
        assertThatThrownBy(() -> ClosedSet.assertClosedSet(List.of(), List.of("POD:FORGED"),
                Set.of("POD:SHP-1"), EDI))
                .isInstanceOf(ClosedSet.ClosedSetViolation.class)
                .hasMessageContaining("POD:FORGED");
        assertThatCode(() -> ClosedSet.assertClosedSet(List.of(), List.of("POD:SHP-1"),
                Set.of("POD:SHP-1"), EDI)).doesNotThrowAnyException();
    }

    @Test
    void edi812BelongsToReferencesOnly() {
        assertThatCode(() -> ClosedSet.assertClosedSet(List.of(EDI), List.of("BOL:SHP-1"),
                Set.of("BOL:SHP-1"), EDI)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ClosedSet.assertClosedSet(List.of(), List.of(EDI),
                Set.of("BOL:SHP-1"), EDI))
                .isInstanceOf(ClosedSet.ClosedSetViolation.class);
        assertThatThrownBy(() -> ClosedSet.assertClosedSet(List.of("EDI812:other"),
                List.of(), Set.of(), EDI))
                .isInstanceOf(ClosedSet.ClosedSetViolation.class);
    }
}
