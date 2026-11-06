INSERT INTO retailer (id, name, time_zone, window_days) VALUES
    ('NYC', 'New York Retail', 'America/New_York', 30)
ON CONFLICT DO NOTHING;

INSERT INTO reason_code_mapping (retailer_id, reason_code, category) VALUES
    ('NYC', 'SHORT', 'SHORTAGE'),
    ('NYC', 'PRICE', 'PRICE_VARIANCE'),
    ('NYC', 'PROMO', 'PROMOTION')
ON CONFLICT DO NOTHING;
