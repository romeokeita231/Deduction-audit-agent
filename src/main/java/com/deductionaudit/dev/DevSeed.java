package com.deductionaudit.dev;

import com.deductionaudit.persistence.DeductionStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Component
@Profile("dev")
public class DevSeed implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final DeductionStore store;

    public DevSeed(JdbcTemplate jdbc, DeductionStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!store.findAllRetailers().isEmpty()) {
            return;
        }
        jdbc.update("INSERT INTO retailer (id, name, time_zone, window_days) VALUES (?, ?, ?, ?)",
                "NORTHWIND", "Northwind Retail", "America/New_York", 30);
        jdbc.update("INSERT INTO retailer (id, name, time_zone, window_days) VALUES (?, ?, ?, ?)",
                "PACIFIC", "Pacific Grocers", "America/Los_Angeles", 21);
        map("NORTHWIND", "SHORT", "SHORTAGE");
        map("NORTHWIND", "DAMAGE", "SHORTAGE");
        map("NORTHWIND", "PRICE", "PRICE_VARIANCE");
        map("NORTHWIND", "PROMO", "PROMOTION");
        map("PACIFIC", "SHORT", "SHORTAGE");
        map("PACIFIC", "PRICE", "PRICE_VARIANCE");

        LocalDate today = LocalDate.now();
        jdbc.update("INSERT INTO asn (shipment_id, declared_qty, po_no) VALUES (?, ?, ?)",
                "SHP-1001", 120, "PO-9001");
        jdbc.update("INSERT INTO bol (shipment_id, shipped_qty, carrier) VALUES (?, ?, ?)",
                "SHP-1001", 120, "XPO");
        jdbc.update("INSERT INTO pod (shipment_id, received_qty, signed_by) VALUES (?, ?, ?)",
                "SHP-1001", 96, "dock-3");
        jdbc.update("INSERT INTO asn (shipment_id, declared_qty, po_no) VALUES (?, ?, ?)",
                "SHP-1002", 80, "PO-9002");
        jdbc.update("INSERT INTO bol (shipment_id, shipped_qty, carrier) VALUES (?, ?, ?)",
                "SHP-1002", 80, "Estes");
        jdbc.update("INSERT INTO pod (shipment_id, received_qty, signed_by) VALUES (?, ?, ?)",
                "SHP-1002", 80, "dock-1");

        jdbc.update("INSERT INTO price_master (sku, valid_from, valid_to, unit_price) VALUES (?, ?, ?, ?)",
                "SKU-1", today.minusDays(90), today.plusDays(90), new BigDecimal("12.50"));
        jdbc.update("INSERT INTO invoice_line (invoice_no, sku, qty, unit_price) VALUES (?, ?, ?, ?)",
                "INV-5001", "SKU-1", 100, new BigDecimal("13.00"));

        jdbc.update("INSERT INTO promotion_agreement (doc_id, title, retailer_id, sku, valid_from, valid_to) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "PA-07", "Winter volume rebate", "NORTHWIND", "SKU-1", today.withDayOfMonth(1).minusMonths(6),
                today.plusMonths(1));
        jdbc.update("INSERT INTO promotion_agreement_clause (doc_id, clause_no, text) VALUES (?, ?, ?)",
                "PA-07", "1", "Retailer earns a 4% rebate on every unit purchased in the rebate window.");
        jdbc.update("INSERT INTO promotion_agreement_clause (doc_id, clause_no, text) VALUES (?, ?, ?)",
                "PA-07", "2", "The rebate is settled within 30 days after the rebate window closes.");
        jdbc.update("INSERT INTO promotion_settlement (doc_id, accrued_amount, settled_amount, settled_on) "
                        + "VALUES (?, ?, ?, ?)",
                "PA-07", new BigDecimal("640.00"), new BigDecimal("480.00"), today.minusDays(5));

        insertDeduction("NORTHWIND", "SHORT", today.minusDays(4), "SHP-1001", null, null,
                new BigDecimal("480.00"));
        insertDeduction("NORTHWIND", "SHORT", today.minusDays(40), "SHP-1002", null, null,
                new BigDecimal("120.00"));
        insertDeduction("NORTHWIND", "PRICE", today.minusDays(2), null, "SKU-1", "INV-5001",
                new BigDecimal("50.00"));
        insertDeduction("PACIFIC", "PROMO", today.minusDays(6), null, "SKU-1", null,
                new BigDecimal("160.00"));
    }

    private void map(String retailerId, String reasonCode, String category) {
        jdbc.update("INSERT INTO reason_code_mapping (retailer_id, reason_code, category) VALUES (?, ?, ?)",
                retailerId, reasonCode, category);
    }

    private void insertDeduction(String retailerId, String reasonCode, LocalDate deductionDate,
                                 String shipmentId, String sku, String invoiceNo, BigDecimal amount) {
        int window = store.findRetailer(retailerId).map(DeductionStore.Retailer::windowDays).orElse(30);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO deduction (id, retailer_id, reason_code, amount, currency, deduction_date,
                                               received_at, window_ends_at, status, shipment_id, sku, invoice_no,
                                               version)
                        VALUES (?, ?, ?, ?, 'USD', ?, now(), ?, 'NEW', ?, ?, ?, 0)
                        """,
                id, retailerId, reasonCode, amount, deductionDate,
                com.deductionaudit.domain.WindowArithmetic.windowEndsAt(deductionDate, window),
                shipmentId, sku, invoiceNo);
    }
}
