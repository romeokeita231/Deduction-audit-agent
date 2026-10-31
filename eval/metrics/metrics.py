import argparse
import hashlib
import json
import re
import shutil
import string
import subprocess
import sys
from datetime import datetime
from pathlib import Path

SCHEMA_VERSION = 2
STATES = ("DEDUCTION_STANDS", "DISPUTABLE", "EVIDENCE_GAP", "POLICY_GAP")
CATEGORY_PREFIX = {
    "SHORTAGE": "SHORTAGE",
    "PRICE": "PRICE_VARIANCE",
    "PROMOTION": "PROMOTION",
}
EVIDENCE_ID = re.compile(r"(?:ASN|BOL|POD|PRICE|INVOICE|PROMO|SETTLE|EDI812):")
CLOSING_MARKERS = ("look forward", "thank you", "do not hesitate", "closing this case",
                   "please confirm", "we appreciate", "regards", "sincerely")


class MetricsError(ValueError):
    pass


def expected_state(row):
    state = row.get("expectedState")
    if state == "INCONCLUSIVE":
        return row.get("expectedParkedReason")
    return state


def actual_state(row):
    verdict = row.get("verdict")
    if verdict == "INCONCLUSIVE":
        return row.get("parkedReason")
    return verdict


def cell_of(row):
    parts = str(row.get("caseId", "")).split("-")
    if len(parts) < 2 or parts[0] not in CATEGORY_PREFIX:
        raise MetricsError("caseId does not carry a category and situation: " + str(row.get("caseId")))
    return CATEGORY_PREFIX[parts[0]], parts[1]


def ratio(k, n):
    return {"k": int(k), "n": int(n)}


def load_rows(path):
    rows = []
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise MetricsError("line " + str(number) + " is not json: " + str(error)) from error
        if "schemaVersion" not in row or "caseId" not in row:
            raise MetricsError("line " + str(number) + " is not an evaluation row")
        rows.append(row)
    if not rows:
        raise MetricsError("the export holds no rows")
    versions = {row["schemaVersion"] for row in rows}
    if len(versions) != 1:
        raise MetricsError("the export mixes schema versions " + str(sorted(versions)))
    return rows


def corpus_sha256(directory):
    digest = hashlib.sha256()
    for path in sorted(Path(directory).glob("*.json"), key=lambda item: item.name):
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def corpus_case_count(directory):
    return len(list(Path(directory).glob("*.json")))


def policy_sha256(path):
    text = Path(path).read_text(encoding="utf-8")
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def resolve_commit(commit, root):
    try:
        result = subprocess.run(["git", "-C", str(root), "rev-parse", commit],
                                capture_output=True, check=True, text=True)
        return result.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return commit


def metric1(rows):
    buckets = {state: 0 for state in STATES}
    for row in rows:
        bucket = expected_state(row)
        if bucket not in buckets:
            raise MetricsError("unknown expected state " + str(bucket) + " in " + row["caseId"])
        buckets[bucket] += 1
    for state, count in buckets.items():
        if count == 0:
            raise MetricsError("the expected state bucket " + state + " is empty")

    match = sum(1 for row in rows if actual_state(row) == expected_state(row))
    recall = {}
    for state in STATES:
        hit = sum(1 for row in rows
                  if expected_state(row) == state and actual_state(row) == state)
        recall[state] = ratio(hit, buckets[state])

    categories = {}
    for prefix, category in CATEGORY_PREFIX.items():
        matching = 0
        total = 0
        for row in rows:
            if str(row["caseId"]).startswith(prefix + "-"):
                total += 1
                if actual_state(row) == expected_state(row):
                    matching += 1
        categories[category] = ratio(matching, total)

    confusion = []
    cells = {}
    for row in rows:
        category, situation = cell_of(row)
        cells.setdefault((category, situation), {state: 0 for state in STATES})
        state = actual_state(row)
        if state not in STATES:
            raise MetricsError("unknown actual state " + str(state) + " in " + row["caseId"])
        cells[(category, situation)][state] += 1
    for (category, situation) in sorted(cells):
        for state in STATES:
            confusion.append({"category": category, "situation": situation, "state": state,
                              "n": cells[(category, situation)][state]})

    return {
        "microExactMatch": ratio(match, len(rows)),
        "stateRecall": recall,
        "categoryExactMatch": categories,
        "confusion": confusion,
    }


def metric2(rows):
    denominator = sum(1 for row in rows if expected_state(row) == "DISPUTABLE")
    no_claim = sum(1 for row in rows if expected_state(row) == "DEDUCTION_STANDS")
    wrong_field = sum(1 for row in rows if expected_state(row) == "EVIDENCE_GAP")
    unarbitrated = sum(1 for row in rows if expected_state(row) == "POLICY_GAP")
    if denominator + no_claim + wrong_field + unarbitrated != len(rows):
        raise MetricsError("the exclusion rows do not add up to the corpus size")

    hits = 0
    expected_ids = 0
    predicted_ids = 0
    claimed = 0
    zero_reference = 0
    for row in rows:
        if expected_state(row) != "DISPUTABLE":
            continue
        claim = row.get("claim")
        if claim is None:
            continue
        claimed += 1
        predicted = list(claim.get("supportingEvidence") or [])
        expected = list(row.get("expectedSupportingEvidence") or [])
        if not predicted:
            zero_reference += 1
        hits += len(set(predicted) & set(expected))
        expected_ids += len(expected)
        predicted_ids += len(predicted)

    precision = ratio(hits, predicted_ids) if predicted_ids else {
        "k": 0, "n": 0, "value": "undefined"}
    return {
        "denominator": {"oracleDisputable": denominator},
        "exclusions": {
            "noClaim": no_claim,
            "wrongField": wrong_field,
            "unarbitrated": unarbitrated,
        },
        "claimed": claimed,
        "zeroReferenceCases": zero_reference,
        "recall": ratio(hits, expected_ids),
        "precision": precision,
    }


def skeleton_passes(row):
    narrative = row.get("narrative")
    contract = row.get("contract") or {}
    if not narrative or not contract:
        return False
    words = narrative.split()
    bounds = contract.get("summaryWords") or {}
    if not bounds.get("min", 0) <= len(words) <= bounds.get("max", 0):
        return False
    paragraphs = [part for part in re.split(r"\n\s*\n", narrative) if part.strip()]
    paragraph_bounds = contract.get("paragraphs") or {}
    if not paragraph_bounds.get("min", 0) <= len(paragraphs) <= paragraph_bounds.get("max", 0):
        return False
    if contract.get("tokenizer") != "whitespace":
        return False
    amount = contract.get("amount")
    if not amount or narrative.count(amount) != 1:
        return False
    amount_at = narrative.find(amount)
    evidence = [match.start() for match in EVIDENCE_ID.finditer(narrative)]
    if not evidence or min(evidence) <= amount_at:
        return False
    first_evidence = min(evidence)
    asks = [match.start() for match in re.finditer(r"\breverse\b", narrative)]
    credit_memo_at = narrative.find("credit memo")
    if credit_memo_at >= 0:
        asks.append(credit_memo_at)
    if not asks or min(asks) <= first_evidence:
        return False
    ask_at = min(asks)
    lowered = narrative.lower()
    closes = [position for position in (lowered.find(marker) for marker in CLOSING_MARKERS)
              if position > ask_at]
    if not closes:
        return False
    close_at = min(closes)
    return not any(position > close_at for position in evidence)


def metric3(rows):
    produced = [row for row in rows if row.get("narrative")]
    passed = sum(1 for row in produced if skeleton_passes(row))
    denominator = sum(1 for row in rows if expected_state(row) == "DISPUTABLE")
    return {
        "structuralPass": ratio(passed, len(produced)),
        "productionRate": ratio(len(produced), denominator),
    }


def negative_controls(path):
    if not Path(path).exists():
        raise MetricsError("the manifest is missing: " + str(path))
    try:
        manifest = json.loads(Path(path).read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise MetricsError("the manifest is not json: " + str(error)) from error
    controls = []
    for entry in manifest.get("cases") or []:
        why = entry.get("why")
        if why:
            controls.append({"caseId": entry["caseId"], "why": why})
    return controls


def compute(rows, corpus_sha="", policy_sha="", commit="", controls=None, limited_by=None,
            controls_source="none", mode="replay", corpus_case_count_value=None):
    versions = {row.get("schemaVersion") for row in rows}
    if len(versions) != 1:
        raise MetricsError("the export mixes schema versions")
    return {
        "schemaVersion": versions.pop(),
        "mode": mode,
        "corpus": {"sha256": corpus_sha, "cases": len(rows)},
        "policy": {"sha256": policy_sha},
        "negativeControls": list(controls or []),
        "controlsSource": controls_source,
        "commit": commit,
        "caseCount": len(rows),
        "corpusCaseCount": len(rows) if corpus_case_count_value is None
        else int(corpus_case_count_value),
        "limitedBy": limited_by,
        "metric1": metric1(rows),
        "metric2": metric2(rows),
        "metric3": metric3(rows),
    }


def pass_count(ratio):
    return int(ratio["k"])


def total_count(ratio):
    return int(ratio["n"])


def format_ratio(ratio):
    return str(pass_count(ratio)) + "/" + str(total_count(ratio))


def format_precision(ratio):
    if ratio.get("value") is not None:
        return str(ratio["value"])
    return format_ratio(ratio)


def state_recall(report):
    return report["metric1"]["stateRecall"]


def category_exact_match(report):
    return report["metric1"]["categoryExactMatch"]


def evidence_scores(report):
    return report["metric2"]


def structure_scores(report):
    return report["metric3"]


def controls_note(report):
    if report["controlsSource"] == "none":
        if report["mode"] == "live":
            return "本次为 live 运行，产出率差额来自模型实际失败，不含人为对照。"
        return "本次为 replay 运行，未配置负向对照。"
    controls = report.get("negativeControls") or []
    if not controls:
        return "负向对照来源为 manifest，本次没有登记负向对照。"
    lines = ["负向对照来自 manifest："]
    for control in controls:
        lines.append("- `" + control["caseId"] + "` — " + control["why"])
    return "\n".join(lines)


def report_date():
    return datetime.now().date().isoformat()


def render_report(report, template):
    evidence = evidence_scores(report)
    structure = structure_scores(report)
    limited = report["limitedBy"]
    short = str(report["commit"])[:7]
    if report["mode"] == "live":
        pipeline_command = "mvn -P live spring-boot:run"
        archive_input = "target/eval/results.ndjson"
    else:
        pipeline_command = "mvn -B test -Dtest=CiFixtureTest"
        archive_input = "docs/eval/reports/" + report_date() + "-" + short + "/results.ndjson"
    archive_command = ("python -m eval.metrics " + archive_input + " --report docs/eval/reports"
                       " --commit " + short + " --mode " + report["mode"])
    if report["mode"] == "replay":
        archive_command += " --manifest src/test/resources/eval/ci-manifest.json"
    if limited:
        pipeline_command += " \"-Dspring-boot.run.arguments=--limit=" + str(limited) + "\""
        archive_command += " --limit " + str(limited)
    values = {
        "date": report_date(),
        "commit": str(report["commit"])[:7],
        "mode": report["mode"],
        "mode_note": "真打模型，不回放 cassette。" if report["mode"] == "live"
        else "回放 cassette，不调用模型。",
        "schema_version": str(report["schemaVersion"]),
        "case_count": str(report["caseCount"]),
        "limited_note": "" if limited is None else " · 截断运行，本次指标不可用于结论（--limit "
        + str(limited) + "）",
        "corpus_sha256": report["corpus"]["sha256"],
        "policy_sha256": report["policy"]["sha256"],
        "controls_note": controls_note(report),
        "micro_exact_match": format_ratio(report["metric1"]["microExactMatch"]),
        "state_recall": "\n".join("- `" + state + "`: " + format_ratio(value)
                                  for state, value in state_recall(report).items()),
        "category_exact_match": "\n".join("- `" + category + "`: " + format_ratio(value)
                                          for category, value in category_exact_match(report).items()),
        "disputable": str(evidence["denominator"]["oracleDisputable"]),
        "no_claim": str(evidence["exclusions"]["noClaim"]),
        "wrong_field": str(evidence["exclusions"]["wrongField"]),
        "unarbitrated": str(evidence["exclusions"]["unarbitrated"]),
        "claimed": str(evidence["claimed"]),
        "zero_reference": str(evidence["zeroReferenceCases"]),
        "precision": format_precision(evidence["precision"]),
        "recall": format_ratio(evidence["recall"]),
        "structural_pass": format_ratio(structure["structuralPass"]),
        "production_rate": format_ratio(structure["productionRate"]),
        "pipeline_command": pipeline_command,
        "archive_command": archive_command,
    }
    return string.Template(Path(template).read_text(encoding="utf-8")).substitute(values)


def archive(report, ndjson, report_root):
    for field in ("mode", "caseCount", "corpusCaseCount", "limitedBy"):
        if field not in report:
            raise MetricsError("the report cannot be archived without " + field)
    directory = Path(report_root) / (report_date() + "-" + str(report["commit"])[:7])
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "metrics.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    template = Path(__file__).resolve().parents[2] / "eval/templates/report.zh.md"
    (directory / "report.md").write_text(render_report(report, template), encoding="utf-8")
    target = directory / "results.ndjson"
    source = Path(ndjson).resolve()
    if source != target.resolve():
        shutil.copyfile(source, target)
    return directory


def main(argv=None):
    parser = argparse.ArgumentParser(prog="eval.metrics")
    parser.add_argument("ndjson")
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--commit", required=True)
    parser.add_argument("--mode", required=True, choices=("live", "replay"))
    parser.add_argument("--limit", type=int)
    parser.add_argument("--corpus")
    parser.add_argument("--policy")
    parser.add_argument("--manifest")
    arguments = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[2]
    corpus = Path(arguments.corpus) if arguments.corpus else root / "src/main/resources/eval/corpus"
    policy = Path(arguments.policy) if arguments.policy else root / "src/main/resources/policy/audit-policy.md"
    try:
        rows = load_rows(arguments.ndjson)
        controls = negative_controls(Path(arguments.manifest)) if arguments.manifest else []
        commit = resolve_commit(arguments.commit, root)
        report = compute(rows, corpus_sha256(corpus), policy_sha256(policy), commit,
                         controls, arguments.limit, "manifest" if arguments.manifest else "none",
                         arguments.mode, corpus_case_count(corpus))
        text = json.dumps(report, indent=2) + "\n"
        if arguments.output:
            Path(arguments.output).write_text(text, encoding="utf-8")
        if arguments.report:
            archive(report, arguments.ndjson, arguments.report)
        if not arguments.output and not arguments.report:
            print(text, end="")
    except MetricsError as error:
        print("metrics failed: " + str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
