# AGENTS.md

Operating notes for agents working in this repository.

## Agent skills

### Issue tracker

Issues and specs live as GitHub issues, managed with the `gh` CLI.
See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage roles use their default label names.
See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root.
See `docs/agents/domain.md`.

## UI work

Load the `apple-design` skill before writing or changing templates or CSS.

Its scope here is narrower than its description. Typography, restraint, feedback and
reduced-motion apply. Gestural, spring and sheet interaction does not: the two pages are
server-rendered over the JSON endpoints, there is no JS build chain, and a table of money
must not animate. Amounts use tabular figures. A countdown changing its value is the only
thing on the queue page that may animate at all.

## Local environment (Windows)

- Git's bundled MSYS `ssh.exe` cannot start under the sandbox (`couldn't create signal pipe`).
  Any networked git command needs:

  ```powershell
  $env:GIT_SSH="C:/Windows/System32/OpenSSH/ssh.exe"; $env:GIT_SSH_VARIANT="ssh"
  ```

- `origin` is `ssh://git@ssh.github.com:443/...`; `github.com:443` is unreachable from this
  machine. `gh` goes through `api.github.com` and works without the override above.
- Design docs and ADRs are written in Chinese; code, comments and commit messages are English.
- Integration tests need Docker, and on this machine they need the TCP socket even though the
  `docker` CLI works without it. Set it before every `mvn test`:

  ```powershell
  $env:DOCKER_HOST="tcp://127.0.0.1:2375"
  ```

  Without it seven Testcontainers tests error out while the rest stay green, which reads like a
  code regression. It is not.
- All data in this repository is synthetic. Never commit generated data, credentials or
  anything from the parent directory. Two exceptions are frozen fixtures and the only
  source of truth, and they are committed on purpose: `src/main/resources/eval/corpus/`
  and `src/test/resources/eval/baseline/`. Every other generated artefact (PG dumps,
  traces, exports) stays out.

## CI

`.github/workflows/ci.yml` is one job with two test steps: `python -m unittest eval.metrics.test_metrics`
and then `./mvnw -B test` (unit tests plus the Testcontainers integration group). Reproduce it locally
with the same two commands. On Windows, set `DOCKER_HOST` first as described above; CI does not, because
Testcontainers finds the socket on the Linux runner. `python` must be 3.12.

First green full run (both container suites, warm Maven cache): 1m24s, 2026-11-07, `ubuntu-latest`.
One-off observation, not a budget assertion.

The containers in `IntegrationTestBase` start once per JVM in a static block, not once per test class:
Spring's context cache outlives a class, so a restarted container gets a new port while the cached
context still points at the old one.

## Eval work

`docs/adr/0009-evaluation-corpus-and-metrics.md` is the contract. Four rules that cost real
time when broken:

- Never hand-edit a generated artefact: `docs/eval/reports/*/report.md`, `metrics.json`,
  `results.ndjson`, `src/test/resources/eval/baseline/*`. Re-run `eval/metrics` instead. A
  hand-edited report cannot be recomputed by a third party, which is the whole point of
  committing it.
- An archive is only reproducible on an empty database at the commit it pins. Running
  `eval:live` twice against the same Docker volume collides on `caseId`-derived keys. Start
  clean and finish with `docker compose down -v`.
- Changing a metric's definition or a structural predicate is a decision, not a fix. When you
  do either, keep the before and after numbers in the report and name which cases moved and
  why. A report with only the new number is indistinguishable from a model that improved.
- Classify what you are doing in the commit message: `fix(eval)` for a checker bug that
  produced wrong numbers, `feat(eval)` for a new capability, `docs` for a rule that changed.
  Mixing a predicate change into a fix commit hides it from the next review.
