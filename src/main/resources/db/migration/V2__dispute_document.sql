CREATE TABLE evidence_version (
    id         integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version    integer NOT NULL,
    updated_at timestamptz NOT NULL
);

INSERT INTO evidence_version (id, version, updated_at) VALUES (1, 1, now());

ALTER TABLE dispute ADD CONSTRAINT dispute_deduction_id_id_key UNIQUE (deduction_id, id);

CREATE TABLE dispute_document (
    dispute_id       uuid PRIMARY KEY REFERENCES dispute (id),
    deduction_id     uuid NOT NULL,
    attempt_no       integer NOT NULL,
    claim            jsonb NOT NULL,
    narrative        text NOT NULL,
    digest           jsonb NOT NULL,
    evidence_version integer NOT NULL,
    prompt_version   text NOT NULL,
    model            text NOT NULL,
    drafted_at       timestamptz NOT NULL,
    FOREIGN KEY (deduction_id, attempt_no) REFERENCES verdict (deduction_id, attempt_no),
    FOREIGN KEY (deduction_id, dispute_id) REFERENCES dispute (deduction_id, id)
);
