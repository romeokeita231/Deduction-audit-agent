package com.deductionaudit.domain;

import java.time.LocalDate;
import java.util.UUID;

public final class EvidenceIds {

    private EvidenceIds() {
    }

    public static String asn(String shipmentId) {
        return "ASN:" + shipmentId;
    }

    public static String bol(String shipmentId) {
        return "BOL:" + shipmentId;
    }

    public static String pod(String shipmentId) {
        return "POD:" + shipmentId;
    }

    public static String price(String sku, LocalDate validFrom) {
        return "PRICE:" + sku + ":" + validFrom;
    }

    public static String invoice(String invoiceNo) {
        return "INVOICE:" + invoiceNo;
    }

    public static String promotion(String docId, String clauseNo) {
        return "PROMO:" + docId + "·§" + clauseNo;
    }

    public static String settlement(String docId) {
        return "SETTLE:" + docId;
    }

    public static String edi812(UUID deductionId) {
        return "EDI812:" + deductionId;
    }
}
