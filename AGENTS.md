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
- Integration tests need Docker. When the daemon socket is not the default, set
  `$env:DOCKER_HOST="tcp://127.0.0.1:2375"`; without it three tests error out.
- All data in this repository is synthetic. Never commit generated data, credentials or
  anything from the parent directory. Two exceptions are frozen fixtures and the only
  source of truth, and they are committed on purpose: `src/main/resources/eval/corpus/`
  and `src/test/resources/eval/baseline/`. Every other generated artefact (PG dumps,
  traces, exports) stays out.
