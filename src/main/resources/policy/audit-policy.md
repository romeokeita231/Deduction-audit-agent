# Audit policy (rules only, never answers)

These rules are the written policy an AR would apply. They are given to the model in the
system prompt. Oracle labels and expected verdicts are never included anywhere the model
can read.

## Shortage and damage

- The ASN states what we declared we would ship. It is a claim made by the Supplier.
- The BOL states what the carrier actually took. The BOL decides the shipped quantity.
- The POD states what the receiver actually accepted. The POD decides the received quantity.
- When the POD quantity matches the BOL quantity, everything the carrier took was delivered, so
  the shortage deduction stands.
- When the POD quantity is below the BOL quantity, the units were lost in transit: the shortage
  deduction is disputable.
- The deduction's own share of the gap is only claimed once; never add it to the POD.
- Do not average the documents and do not give the EDI 812 the final word.
- Damage claims follow the same evidence: the POD is the record of what arrived. If the POD
  is missing, the answer is INCONCLUSIVE with missingEvidence containing POD.
- If the BOL is missing, the answer is INCONCLUSIVE with missingEvidence containing BOL.

## Price variance

- The price master is authoritative for a SKU on a given day. A deduction date falls inside
  exactly one validity interval; use the interval that contains the deduction date.
- The invoice line is what we actually billed. Compare the invoice line unit price with the
  price master unit price for the effective interval.
- If the invoice line matches the master, the deduction stands.
- If the invoice line was billed above the master, the overbilling supports the retailer's
  deduction: the deduction stands.
- If the invoice line was billed below the master, the retailer's price variance deduction is
  not supported: it is disputable.
- If the invoice line is missing, the answer is INCONCLUSIVE with missingEvidence containing
  INVOICE_LINE.
- If the price master has no interval covering the deduction date, the answer is INCONCLUSIVE
  with empty missingEvidence (a policy gap).

## Promotion

- A promotion agreement is authoritative through its numbered clauses. Cite a clause as
  `<docId> § <clauseNo>`.
- The settlement record states what was accrued and what was actually settled.
- When the agreement supports more than the settled amount was paid, the deduction is
  disputable.
- When the settlement matches the agreement, the deduction stands.
- When the agreement clauses conflict with a summary in this prompt, the agreement clause
  wins. The document is the contract; this prompt is a convenience.
- If the settlement record is missing, the answer is INCONCLUSIVE with missingEvidence
  containing PROMOTION_SETTLEMENT.

## Evidence rules

- Only evidence IDs returned by tools during this run may be cited. The deduction's own EDI
  812 may appear in `references`, never in `supportingEvidence`.
- Missing documents are named in `missingEvidence` using the enum values POD, BOL, ASN,
  INVOICE_LINE, PROMOTION_SETTLEMENT.
