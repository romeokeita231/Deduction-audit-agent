package com.deductionaudit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Deduction(
        UUID id,
        String retailerId,
        String reasonCode,
        DeductionCategory category,
        BigDecimal amount,
        String currency,
        LocalDate deductionDate,
        Instant receivedAt,
        LocalDate windowEndsAt,
        DeductionStatus status,
        ParkedReason parkedReason,
        String parkedPolicyVersion,
        String invoiceNo,
        String poNo,
        String shipmentId,
        String sku,
        long version) {

    public Deduction withStatus(DeductionStatus newStatus, ParkedReason newParkedReason,
                                String newParkedPolicyVersion, long newVersion) {
        return new Deduction(id, retailerId, reasonCode, category, amount, currency, deductionDate,
                receivedAt, windowEndsAt, newStatus, newParkedReason, newParkedPolicyVersion, invoiceNo,
                poNo, shipmentId, sku, newVersion);
    }

    public Deduction withCategory(DeductionCategory newCategory) {
        return new Deduction(id, retailerId, reasonCode, newCategory, amount, currency, deductionDate,
                receivedAt, windowEndsAt, status, parkedReason, parkedPolicyVersion, invoiceNo,
                poNo, shipmentId, sku, version);
    }
}
