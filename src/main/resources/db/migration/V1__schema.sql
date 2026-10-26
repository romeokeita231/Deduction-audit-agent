CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE retailer (
    id          text PRIMARY KEY,
    name        text NOT NULL,
    time_zone   text NOT NULL,
    window_days integer NOT NULL
);

CREATE TABLE reason_code_mapping (
    retailer_id     text NOT NULL REFERENCES retailer (id),
    reason_code     text NOT NULL,
    category        text NOT NULL,
    mapping_version integer NOT NULL DEFAULT 1,
    PRIMARY KEY (retailer_id, reason_code, mapping_version)
);

CREATE TABLE deduction (
    id                     uuid PRIMARY KEY,
    retailer_id            text NOT NULL REFERENCES retailer (id),
    reason_code            text NOT NULL,
    category               text,
    amount                 numeric(12, 2) NOT NULL,
    currency               text NOT NULL,
    deduction_date         date NOT NULL,
    received_at            timestamptz NOT NULL,
    window_ends_at         date NOT NULL,
    status                 text NOT NULL,
    parked_reason          text,
    parked_policy_version  text,
    invoice_no             text,
    po_no                  text,
    shipment_id            text,
    sku                    text,
    version                bigint NOT NULL DEFAULT 0
);

CREATE TABLE deduction_transition (
    deduction_id uuid NOT NULL REFERENCES deduction (id),
    seq          integer NOT NULL,
    from_status  text,
    to_status    text NOT NULL,
    event        text NOT NULL,
    attempt_no   integer,
    occurred_at  timestamptz NOT NULL,
    PRIMARY KEY (deduction_id, seq)
);

CREATE TABLE dispute (
    id           uuid PRIMARY KEY,
    deduction_id uuid NOT NULL UNIQUE REFERENCES deduction (id),
    opened_at    timestamptz,
    closed_at    timestamptz,
    result       text,
    submitted_by text,
    note         text
);

CREATE TABLE verdict (
    deduction_id        uuid NOT NULL REFERENCES deduction (id),
    attempt_no          integer NOT NULL,
    verdict             text NOT NULL,
    missing_evidence    text[] NOT NULL DEFAULT '{}',
    reference_ids       text[] NOT NULL DEFAULT '{}',
    supporting_evidence text[] NOT NULL DEFAULT '{}',
    reasoning           text,
    prompt_version      text NOT NULL,
    policy_version      text NOT NULL,
    mapping_version     integer NOT NULL,
    model               text NOT NULL,
    evaluated_at        timestamptz NOT NULL,
    PRIMARY KEY (deduction_id, attempt_no)
);

CREATE TABLE evaluation_attempt (
    deduction_id    uuid NOT NULL REFERENCES deduction (id),
    attempt_no      integer NOT NULL,
    started_at      timestamptz NOT NULL,
    ended_at        timestamptz,
    outcome         text,
    tool_call_count integer NOT NULL DEFAULT 0,
    PRIMARY KEY (deduction_id, attempt_no)
);

CREATE TABLE asn (
    shipment_id  text PRIMARY KEY,
    declared_qty integer NOT NULL,
    shipped_at   timestamptz,
    po_no        text
);

CREATE TABLE bol (
    shipment_id text PRIMARY KEY,
    shipped_qty integer NOT NULL,
    carrier     text,
    shipped_at  timestamptz
);

CREATE TABLE pod (
    shipment_id  text PRIMARY KEY,
    received_qty integer NOT NULL,
    received_at  timestamptz,
    signed_by    text
);

CREATE TABLE price_master (
    sku         text NOT NULL,
    valid_from  date NOT NULL,
    valid_to    date,
    unit_price  numeric(12, 2) NOT NULL,
    PRIMARY KEY (sku, valid_from)
);

CREATE TABLE invoice_line (
    invoice_no text PRIMARY KEY,
    sku        text NOT NULL,
    qty        integer NOT NULL,
    unit_price numeric(12, 2) NOT NULL
);

CREATE TABLE promotion_agreement (
    doc_id      text PRIMARY KEY,
    title       text NOT NULL,
    retailer_id text,
    sku         text,
    valid_from  date,
    valid_to    date
);

CREATE TABLE promotion_agreement_clause (
    doc_id    text NOT NULL REFERENCES promotion_agreement (doc_id),
    clause_no text NOT NULL,
    text      text NOT NULL,
    embedding vector(1024),
    PRIMARY KEY (doc_id, clause_no)
);

CREATE TABLE promotion_settlement (
    doc_id         text PRIMARY KEY REFERENCES promotion_agreement (doc_id),
    accrued_amount numeric(12, 2) NOT NULL,
    settled_amount numeric(12, 2) NOT NULL,
    settled_on     date
);

CREATE INDEX deduction_status_idx ON deduction (status);
CREATE INDEX deduction_window_idx ON deduction (window_ends_at);
CREATE INDEX reason_code_mapping_lookup_idx ON reason_code_mapping (retailer_id, reason_code, mapping_version DESC);
