DELETE FROM dispute;
DELETE FROM verdict;
DELETE FROM evaluation_attempt;
DELETE FROM deduction_transition;
DELETE FROM deduction;
DELETE FROM promotion_settlement;
DELETE FROM promotion_agreement_clause;
DELETE FROM promotion_agreement;
DELETE FROM invoice_line;
DELETE FROM price_master;
DELETE FROM pod;
DELETE FROM bol;
DELETE FROM asn;
DELETE FROM reason_code_mapping;
DELETE FROM retailer;

INSERT INTO retailer (id, name, time_zone, window_days) VALUES
    ('NYC', 'New York Retail', 'America/New_York', 30),
    ('LA', 'Los Angeles Retail', 'America/Los_Angeles', 30),
    ('TOKYO', 'Tokyo Retail', 'Asia/Tokyo', 30);

INSERT INTO reason_code_mapping (retailer_id, reason_code, category) VALUES
    ('NYC', 'SHORT', 'SHORTAGE'),
    ('NYC', 'PRICE', 'PRICE_VARIANCE'),
    ('NYC', 'PROMO', 'PROMOTION'),
    ('LA', 'SHORT', 'SHORTAGE'),
    ('TOKYO', 'SHORT', 'SHORTAGE');

INSERT INTO deduction (id, retailer_id, reason_code, amount, currency, deduction_date, received_at,
                       window_ends_at, status, shipment_id, sku, invoice_no, version)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'NYC', 'SHORT', 480.00, 'USD', '2026-03-05', now(),
     '2026-04-04', 'NEW', 'SHP-DISPUTE', NULL, NULL, 0),
    ('22222222-2222-2222-2222-222222222222', 'NYC', 'SHORT', 200.00, 'USD', '2026-03-05', now(),
     '2026-04-04', 'NEW', 'SHP-STANDS', NULL, NULL, 0),
    ('33333333-3333-3333-3333-333333333333', 'NYC', 'SHORT', 150.00, 'USD', '2026-03-05', now(),
     '2026-04-04', 'NEW', 'SHP-NOPOD', NULL, NULL, 0),
    ('44444444-4444-4444-4444-444444444444', 'NYC', 'MYSTERY', 99.00, 'USD', '2026-03-05', now(),
     '2026-04-04', 'NEW', 'SHP-FOUR', NULL, NULL, 0),
    ('55555555-5555-5555-5555-555555555555', 'NYC', 'PRICE', 50.00, 'USD', '2026-03-01', now(),
     '2026-03-31', 'NEW', NULL, 'SKU-1', 'INV-LOW', 0),
    ('66666666-6666-6666-6666-666666666666', 'NYC', 'PRICE', 80.00, 'USD', '2026-03-01', now(),
     '2026-03-31', 'NEW', NULL, 'SKU-1', 'INV-HIGH', 0),
    ('77777777-7777-7777-7777-777777777777', 'NYC', 'PRICE', 70.00, 'USD', '2026-03-01', now(),
     '2026-03-31', 'NEW', NULL, 'SKU-1', 'INV-MISSING', 0),
    ('88888888-8888-8888-8888-888888888888', 'NYC', 'PRICE', 60.00, 'USD', '2026-03-01', now(),
     '2026-03-31', 'NEW', NULL, 'SKU-2', 'INV-SKU2', 0),
    ('99999999-9999-9999-9999-999999999999', 'NYC', 'PROMO', 160.00, 'USD', '2026-03-01', now(),
     '2026-03-31', 'NEW', NULL, 'SKU-1', NULL, 0),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'NYC', 'PROMO', 120.00, 'USD', '2026-03-01', now(),
     '2026-03-31', 'NEW', NULL, 'SKU-1', NULL, 0),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'TOKYO', 'SHORT', 240.00, 'USD', '2026-01-01', now(),
     '2026-03-09', 'NEW', 'SHP-OLD', NULL, NULL, 0),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'NYC', 'SHORT', 240.00, 'USD', '2026-03-05', now(),
     '2026-04-04', 'NEW', 'SHP-RETRY', NULL, NULL, 0),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'NYC', 'SHORT', 240.00, 'USD', '2026-03-05', now(),
     '2026-04-04', 'NEW', 'SHP-CLOSEDSET', NULL, NULL, 0),
    ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'NYC', 'SHORT', 240.00, 'USD', '2026-03-05', now(),
     '2026-04-04', 'NEW', 'SHP-LIMIT', NULL, NULL, 0),
    ('15151515-1515-1515-1515-151515151515', 'TOKYO', 'SHORT', 100.00, 'USD', '2026-02-08', now(),
     '2026-03-10', 'NEW', 'SHP-TYO', NULL, NULL, 0),
    ('16161616-1616-1616-1616-161616161616', 'LA', 'SHORT', 100.00, 'USD', '2026-02-08', now(),
     '2026-03-10', 'NEW', 'SHP-LA', NULL, NULL, 0),
    ('17171717-1717-1717-1717-171717171717', 'NYC', 'SHORT', 300.00, 'USD', '2026-03-05', now(),
     '2026-03-10', 'PARKED', 'SHP-PARKED', NULL, NULL, 0),
    ('18181818-1818-1818-1818-181818181818', 'NYC', 'SHORT', 240.00, 'USD', '2026-03-05', now(),
     '2026-04-04', 'NEW', 'SHP-TIMEOUT', NULL, NULL, 0),
    ('20202020-2020-2020-2020-202020202020', 'NYC', 'PROMO', 100.00, 'USD', '2026-03-01', now(),
     '2026-03-31', 'NEW', NULL, 'SKU-1', NULL, 0);

UPDATE deduction d SET category = m.category
FROM reason_code_mapping m
WHERE m.retailer_id = d.retailer_id AND m.reason_code = d.reason_code;

UPDATE deduction SET parked_reason = 'EVIDENCE_GAP'
WHERE id = '17171717-1717-1717-1717-171717171717';

INSERT INTO asn (shipment_id, declared_qty, po_no) VALUES
    ('SHP-DISPUTE', 120, 'PO-1'),
    ('SHP-STANDS', 100, 'PO-2'),
    ('SHP-NOPOD', 100, 'PO-3'),
    ('SHP-FOUR', 50, 'PO-4'),
    ('SHP-OLD', 10, 'PO-5'),
    ('SHP-RETRY', 20, 'PO-6'),
    ('SHP-CLOSEDSET', 30, 'PO-7'),
    ('SHP-LIMIT', 40, 'PO-8'),
    ('SHP-TYO', 5, 'PO-9'),
    ('SHP-LA', 5, 'PO-10'),
    ('SHP-PARKED', 6, 'PO-11'),
    ('SHP-TIMEOUT', 7, 'PO-12');

INSERT INTO bol (shipment_id, shipped_qty, carrier) VALUES
    ('SHP-DISPUTE', 120, 'XPO'),
    ('SHP-STANDS', 100, 'Estes'),
    ('SHP-NOPOD', 100, 'XPO'),
    ('SHP-FOUR', 50, 'XPO'),
    ('SHP-OLD', 10, 'XPO'),
    ('SHP-RETRY', 20, 'XPO'),
    ('SHP-CLOSEDSET', 30, 'XPO'),
    ('SHP-LIMIT', 40, 'XPO'),
    ('SHP-TYO', 5, 'XPO'),
    ('SHP-LA', 5, 'XPO'),
    ('SHP-PARKED', 6, 'XPO'),
    ('SHP-TIMEOUT', 7, 'XPO');

INSERT INTO pod (shipment_id, received_qty, signed_by) VALUES
    ('SHP-DISPUTE', 96, 'dock-1'),
    ('SHP-STANDS', 100, 'dock-1'),
    ('SHP-FOUR', 40, 'dock-1'),
    ('SHP-OLD', 10, 'dock-1'),
    ('SHP-RETRY', 10, 'dock-1'),
    ('SHP-CLOSEDSET', 30, 'dock-1'),
    ('SHP-LIMIT', 40, 'dock-1'),
    ('SHP-TYO', 5, 'dock-1'),
    ('SHP-LA', 5, 'dock-1'),
    ('SHP-PARKED', 6, 'dock-1'),
    ('SHP-TIMEOUT', 7, 'dock-1');

INSERT INTO price_master (sku, valid_from, valid_to, unit_price) VALUES
    ('SKU-1', '2025-12-01', '2026-06-30', 12.50),
    ('SKU-1', '2026-01-01', '2026-01-31', 11.00);

INSERT INTO invoice_line (invoice_no, sku, qty, unit_price) VALUES
    ('INV-LOW', 'SKU-1', 10, 12.00),
    ('INV-HIGH', 'SKU-1', 10, 13.00),
    ('INV-SKU2', 'SKU-2', 5, 9.00);

INSERT INTO promotion_agreement (doc_id, title, retailer_id, sku, valid_from, valid_to) VALUES
    ('PA-07', 'Winter volume rebate', 'NYC', 'SKU-1', '2026-01-01', '2026-06-30'),
    ('PA-13', 'Autumn allowance', 'NYC', 'SKU-1', '2026-01-01', '2026-06-30');

INSERT INTO promotion_agreement (doc_id, title) VALUES
    ('PA-01', 'Synthetic agreement 01'),
    ('PA-02', 'Synthetic agreement 02'),
    ('PA-03', 'Synthetic agreement 03'),
    ('PA-04', 'Synthetic agreement 04'),
    ('PA-05', 'Synthetic agreement 05'),
    ('PA-06', 'Synthetic agreement 06'),
    ('PA-08', 'Synthetic agreement 08'),
    ('PA-09', 'Synthetic agreement 09'),
    ('PA-10', 'Synthetic agreement 10'),
    ('PA-11', 'Synthetic agreement 11'),
    ('PA-12', 'Synthetic agreement 12'),
    ('PA-14', 'Synthetic agreement 14'),
    ('PA-15', 'Synthetic agreement 15'),
    ('PA-16', 'Synthetic agreement 16'),
    ('PA-17', 'Synthetic agreement 17'),
    ('PA-18', 'Synthetic agreement 18'),
    ('PA-19', 'Synthetic agreement 19'),
    ('PA-20', 'Synthetic agreement 20');

INSERT INTO promotion_agreement_clause (doc_id, clause_no, text) VALUES
    ('PA-07', '3.1', 'Retailer earns a four percent rebate on every unit purchased in the rebate window.'),
    ('PA-07', '3.2', 'The rebate is settled within 30 days after the rebate window closes.'),
    ('PA-13', '3.1', 'Retailer earns a three percent allowance on autumn units purchased.'),
    ('PA-13', '3.2', 'Supplier remits the promotion allowance after the period closes, within thirty days.');

INSERT INTO promotion_agreement_clause (doc_id, clause_no, text) VALUES
    ('PA-01', '1', 'Claims must be filed within fourteen days of the delivery date.'),
    ('PA-02', '1', 'Freight is the responsibility of the supplier until the dock door.'),
    ('PA-03', '1', 'Retailer may return unsold seasonal units at full credit.'),
    ('PA-04', '1', 'Damaged units are photographed before disposal.'),
    ('PA-05', '1', 'Invoice disputes must reference the purchase order number.'),
    ('PA-06', '1', 'The supplier provides a monthly sales report.'),
    ('PA-08', '1', 'Cold chain temperature logs are kept for ninety days.'),
    ('PA-09', '1', 'Shortage claims require a signed proof of delivery.'),
    ('PA-10', '1', 'Promotional pricing is communicated two weeks in advance.'),
    ('PA-11', '1', 'The retailer may audit the supplier once per year.'),
    ('PA-12', '1', 'Payment terms are net forty five days from invoice.'),
    ('PA-14', '1', 'Returns must be authorized by the category manager.'),
    ('PA-15', '1', 'Marketing funds are invoiced quarterly in arrears.'),
    ('PA-16', '1', 'The supplier maintains product liability insurance.'),
    ('PA-17', '1', 'Delivery appointments are booked through the portal.'),
    ('PA-18', '1', 'Excess inventory is stored at the retailer warehouse.'),
    ('PA-19', '1', 'The agreement renews automatically for one year.'),
    ('PA-20', '1', 'Notices are sent to the registered contact address.');

INSERT INTO promotion_settlement (doc_id, accrued_amount, settled_amount, settled_on) VALUES
    ('PA-07', 640.00, 400.00, '2026-02-20'),
    ('PA-01', 100.00, 100.00, '2026-02-20');
