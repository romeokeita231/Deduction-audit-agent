package com.deductionaudit.tools;

import com.deductionaudit.domain.EvidenceIds;
import com.deductionaudit.embedding.EmbeddingClient;
import com.deductionaudit.embedding.EmbeddingUnavailableException;
import com.deductionaudit.persistence.EvidenceStore;
import com.deductionaudit.persistence.EvidenceStore.AsnRow;
import com.deductionaudit.persistence.EvidenceStore.BolRow;
import com.deductionaudit.persistence.EvidenceStore.ClauseRow;
import com.deductionaudit.persistence.EvidenceStore.InvoiceRow;
import com.deductionaudit.persistence.EvidenceStore.PodRow;
import com.deductionaudit.persistence.EvidenceStore.PriceRow;
import com.deductionaudit.persistence.EvidenceStore.SettlementRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EvidenceTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EvidenceStore store;
    private final EmbeddingClient embeddingClient;

    public EvidenceTools(EvidenceStore store, EmbeddingClient embeddingClient) {
        this.store = store;
        this.embeddingClient = embeddingClient;
    }

    @Tool(description = "Look up the advance ship notice (ASN) for a shipment. Returns the quantity "
            + "the supplier declared it would ship.")
    public String getAsn(String shipmentId) {
        return store.asn(shipmentId).map(asn -> json(Map.of(
                        "found", true,
                        "evidenceIds", List.of(EvidenceIds.asn(shipmentId)),
                        "document", asnMap(asn))))
                .orElseGet(() -> notFound("ASN"));
    }

    @Tool(description = "Look up the bill of lading (BOL) for a shipment. Returns the quantity the "
            + "carrier actually took from the supplier.")
    public String getBol(String shipmentId) {
        return store.bol(shipmentId).map(bol -> json(Map.of(
                        "found", true,
                        "evidenceIds", List.of(EvidenceIds.bol(shipmentId)),
                        "document", bolMap(bol))))
                .orElseGet(() -> notFound("BOL"));
    }

    @Tool(description = "Look up the proof of delivery (POD) for a shipment. Returns the quantity the "
            + "retailer's receiver actually accepted.")
    public String getPod(String shipmentId) {
        return store.pod(shipmentId).map(pod -> json(Map.of(
                        "found", true,
                        "evidenceIds", List.of(EvidenceIds.pod(shipmentId)),
                        "document", podMap(pod))))
                .orElseGet(() -> notFound("POD"));
    }

    @Tool(description = "Look up the invoice line for an invoice number. Returns the SKU, quantity and "
            + "unit price we actually billed.")
    public String getInvoiceLine(String invoiceNo) {
        return store.invoiceLine(invoiceNo).map(line -> json(Map.of(
                        "found", true,
                        "evidenceIds", List.of(EvidenceIds.invoice(invoiceNo)),
                        "document", invoiceMap(line))))
                .orElseGet(() -> notFound("INVOICE_LINE"));
    }

    @Tool(description = "Look up the price master intervals for a SKU and return the interval effective "
            + "on the given date (ISO yyyy-MM-dd).")
    public String getPriceMaster(String sku, String onDate) {
        LocalDate date = LocalDate.parse(onDate);
        List<PriceRow> intervals = store.priceIntervals(sku);
        PriceRow effective = store.priceAt(sku, date).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("found", effective != null || !intervals.isEmpty());
        body.put("sku", sku);
        body.put("onDate", date.toString());
        body.put("evidenceIds", effective == null ? List.of() : List.of(EvidenceIds.price(sku, effective.validFrom())));
        body.put("effective", effective == null ? null : priceMap(effective));
        List<Map<String, Object>> all = new ArrayList<>();
        for (PriceRow row : intervals) {
            all.add(priceMap(row));
        }
        body.put("intervals", all);
        return json(body);
    }

    @Tool(description = "Search promotion agreement clauses. The query may be a natural language question "
            + "or an agreement document id such as PA-07. Returns clauses with docId, clause number, text "
            + "and stable evidence ids.")
    public String searchPromotionAgreements(String query) {
        List<ClauseRow> clauses = store.clauses(query);
        if (clauses.isEmpty()) {
            try {
                float[] vector = embeddingClient.embed(query);
                clauses = isZero(vector) ? List.of() : store.searchClauses(vector, 5).stream()
                        .filter(clause -> clause.similarity() >= 0.4)
                        .toList();
            } catch (EmbeddingUnavailableException e) {
                clauses = store.lexicalClauses(query, 5);
            }
        }
        List<Map<String, Object>> documents = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (ClauseRow clause : clauses) {
            String id = EvidenceIds.promotion(clause.docId(), clause.clauseNo());
            ids.add(id);
            documents.add(Map.of("evidenceIds", List.of(id), "docId", clause.docId(),
                    "clauseNo", clause.clauseNo(), "text", clause.text(),
                    "similarity", clause.similarity()));
        }
        return json(Map.of("found", !documents.isEmpty(), "query", query, "evidenceIds", ids,
                "clauses", documents));
    }

    @Tool(description = "Look up how much of a promotion agreement was accrued and how much was actually "
            + "settled.")
    public String getPromotionSettlement(String docId) {
        return store.settlement(docId).map(settlement -> json(Map.of(
                        "found", true,
                        "evidenceIds", List.of(EvidenceIds.settlement(docId)),
                        "document", settlementMap(settlement))))
                .orElseGet(() -> notFound("PROMOTION_SETTLEMENT"));
    }

    private static Map<String, Object> asnMap(AsnRow asn) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("evidenceId", EvidenceIds.asn(asn.shipmentId()));
        map.put("shipmentId", asn.shipmentId());
        map.put("declaredQty", asn.declaredQty());
        map.put("shippedAt", asn.shippedAt() == null ? null : asn.shippedAt().toString());
        map.put("poNo", asn.poNo());
        return map;
    }

    private static Map<String, Object> bolMap(BolRow bol) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("evidenceId", EvidenceIds.bol(bol.shipmentId()));
        map.put("shipmentId", bol.shipmentId());
        map.put("shippedQty", bol.shippedQty());
        map.put("carrier", bol.carrier());
        map.put("shippedAt", bol.shippedAt() == null ? null : bol.shippedAt().toString());
        return map;
    }

    private static Map<String, Object> podMap(PodRow pod) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("evidenceId", EvidenceIds.pod(pod.shipmentId()));
        map.put("shipmentId", pod.shipmentId());
        map.put("receivedQty", pod.receivedQty());
        map.put("receivedAt", pod.receivedAt() == null ? null : pod.receivedAt().toString());
        map.put("signedBy", pod.signedBy());
        return map;
    }

    private static Map<String, Object> invoiceMap(InvoiceRow line) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("evidenceId", EvidenceIds.invoice(line.invoiceNo()));
        map.put("invoiceNo", line.invoiceNo());
        map.put("sku", line.sku());
        map.put("qty", line.qty());
        map.put("unitPrice", line.unitPrice());
        return map;
    }

    private static Map<String, Object> priceMap(PriceRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("evidenceId", EvidenceIds.price(row.sku(), row.validFrom()));
        map.put("sku", row.sku());
        map.put("validFrom", row.validFrom().toString());
        map.put("validTo", row.validTo() == null ? null : row.validTo().toString());
        map.put("unitPrice", row.unitPrice());
        return map;
    }

    private static Map<String, Object> settlementMap(SettlementRow settlement) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("evidenceId", EvidenceIds.settlement(settlement.docId()));
        map.put("docId", settlement.docId());
        map.put("accruedAmount", settlement.accruedAmount());
        map.put("settledAmount", settlement.settledAmount());
        map.put("settledOn", settlement.settledOn() == null ? null : settlement.settledOn().toString());
        return map;
    }

    private static boolean isZero(float[] vector) {
        for (float value : vector) {
            if (value != 0f) {
                return false;
            }
        }
        return true;
    }

    private static String notFound(String kind) {
        return json(Map.of("found", false, "evidenceIds", List.of(), "missing", kind));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("tool payload could not be serialized", e);
        }
    }
}
