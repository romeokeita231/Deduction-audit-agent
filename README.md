# Deduction Audit Agent

A supplier-side system for auditing trade deductions and running disputes with retailers.

A retailer takes money out of an invoice and attaches a reason code. This system decides
whether each deduction stands, whether it is worth disputing, and how much of the dispute
window is left — then drafts a dispute with cited evidence.

**Status:** design phase. The domain model, state machine and architecture decisions are
written down before the runtime exists.

All data in this repository is synthetic.

## Design documents

- [Glossary](GLOSSARY.md) — the vocabulary this system uses, and the words it avoids
- [Scope and milestones](docs/PLAN.md) — what it does, and what it deliberately does not
- [Architecture](docs/ARCHITECTURE.md)
- [State machine](docs/STATE-MACHINE.md)
- [Decision records](docs/adr/) — ADR 0001–0007
