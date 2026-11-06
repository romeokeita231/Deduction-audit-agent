package com.deductionaudit.eval;

import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.eval.corpus.CorpusCase;
import com.deductionaudit.eval.corpus.CorpusCase.Agreement;
import com.deductionaudit.eval.corpus.CorpusCase.Asn;
import com.deductionaudit.eval.corpus.CorpusCase.Bol;
import com.deductionaudit.eval.corpus.CorpusCase.Clause;
import com.deductionaudit.eval.corpus.CorpusCase.ClauseEffect;
import com.deductionaudit.eval.corpus.CorpusCase.EvalCell;
import com.deductionaudit.eval.corpus.CorpusCase.Evidence;
import com.deductionaudit.eval.corpus.CorpusCase.InvoiceLine;
import com.deductionaudit.eval.corpus.CorpusCase.Pod;
import com.deductionaudit.eval.corpus.CorpusCase.PriceInterval;
import com.deductionaudit.eval.corpus.CorpusCase.Settlement;
import com.deductionaudit.eval.corpus.CorpusCase.Situation;
import com.deductionaudit.eval.corpus.CorpusEntry;
import com.deductionaudit.eval.oracle.Oracle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

public final class CorpusGenerator {

    public static final long SEED = 20260310L;
    public static final int CASES_PER_CELL = 7;
    public static final int ARBITRATED_DISPUTABLE_MINIMUM = 2;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Oracle ORACLE = new Oracle();

    static {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        printer.indentArraysWith(new DefaultIndenter("  ", "\n"));
        JSON.setDefaultPrettyPrinter(printer);
    }

    private CorpusGenerator() {
    }

    public static void main(String[] args) {
        Path target = args.length > 0 ? Path.of(args[0])
                : Path.of("src/main/resources/eval/corpus");
        write(target);
    }

    public static List<CorpusEntry> generate() {
        List<CorpusEntry> cases = new ArrayList<>();
        for (DeductionCategory category : DeductionCategory.values()) {
            for (Situation situation : Situation.values()) {
                for (int index = 0; index < CASES_PER_CELL; index++) {
                    int ordinal = ordinal(category, situation, index);
                    cases.add(facts(category, situation, index, ordinal));
                }
            }
        }
        validate(cases);
        return cases;
    }

    public static void write(Path directory) {
        try {
            Files.createDirectories(directory);
            try (Stream<Path> existing = Files.list(directory)) {
                for (Path path : existing.filter(p -> p.toString().endsWith(".json")).toList()) {
                    Files.delete(path);
                }
            }
            for (CorpusEntry entry : generate()) {
                JSON.writeValue(directory.resolve(entry.facts().caseId() + ".json").toFile(), entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the evaluation corpus cannot be written", e);
        }
    }

    private static void validate(List<CorpusEntry> cases) {
        Map<DeductionCategory, Map<Situation, Integer>> cells = new EnumMap<>(DeductionCategory.class);
        Map<String, Integer> states = new LinkedHashMap<>();
        for (String state : List.of("DEDUCTION_STANDS", "DISPUTABLE", "EVIDENCE_GAP", "POLICY_GAP")) {
            states.put(state, 0);
        }
        Map<DeductionCategory, Integer> arbitratedDisputable = new EnumMap<>(DeductionCategory.class);
        for (DeductionCategory category : DeductionCategory.values()) {
            cells.put(category, new EnumMap<>(Situation.class));
            arbitratedDisputable.put(category, 0);
        }
        for (CorpusEntry entry : cases) {
            CorpusCase facts = entry.facts();
            cells.get(facts.cell().category()).merge(facts.cell().situation(), 1, Integer::sum);
            states.merge(entry.stateBucket(), 1, Integer::sum);
            if (facts.cell().situation() == Situation.CONFLICT_ARBITRATED
                    && entry.expectation().expectedState() == VerdictValue.DISPUTABLE) {
                arbitratedDisputable.merge(facts.cell().category(), 1, Integer::sum);
            }
        }
        for (DeductionCategory category : DeductionCategory.values()) {
            for (Situation situation : Situation.values()) {
                if (cells.get(category).getOrDefault(situation, 0) != CASES_PER_CELL) {
                    throw new IllegalStateException("cell " + category + "/" + situation
                            + " does not hold " + CASES_PER_CELL + " cases");
                }
            }
            if (arbitratedDisputable.get(category) < ARBITRATED_DISPUTABLE_MINIMUM) {
                throw new IllegalStateException("arbitrated " + category
                        + " has fewer than " + ARBITRATED_DISPUTABLE_MINIMUM + " disputable cases");
            }
        }
        for (Map.Entry<String, Integer> bucket : states.entrySet()) {
            if (bucket.getValue() == 0) {
                throw new IllegalStateException("the expectation bucket " + bucket.getKey() + " is empty");
            }
        }
    }

    private record CategorySpec(String prefix, String reasonCode, Evidence evidence) {
    }

    private static CorpusEntry facts(DeductionCategory category, Situation situation, int index,
                                     int ordinal) {
        Random random = new Random(SEED + ordinal);
        int receivedDaysAgo = 1 + random.nextInt(5);
        int deductionDaysAgo = 7 + random.nextInt(13);
        BigDecimal amount = money(12000 + random.nextInt(30) * 1000);
        CategorySpec spec = switch (category) {
            case SHORTAGE -> new CategorySpec("SHORTAGE", "SHORT",
                    shortage(situation, index, random, deductionDaysAgo, shipmentId(ordinal)));
            case PRICE_VARIANCE -> new CategorySpec("PRICE", "PRICE",
                    price(situation, index, random, deductionDaysAgo, sku(ordinal), invoiceNo(ordinal)));
            case PROMOTION -> new CategorySpec("PROMOTION", "PROMO",
                    promotion(situation, index, random, deductionDaysAgo, sku(ordinal), docId(ordinal)));
        };
        CorpusCase facts = new CorpusCase(
                spec.prefix() + "-" + situation + "-" + String.format("%02d", index + 1),
                new EvalCell(category, situation), SEED + ordinal, "NYC", spec.reasonCode(), amount, "USD",
                receivedDaysAgo, deductionDaysAgo, 30, spec.evidence());
        return new CorpusEntry(facts, ORACLE.expectation(facts));
    }

    private static Evidence shortage(Situation situation, int index, Random random,
                                     int deductionDaysAgo, String shipmentId) {
        int shipped = 20 + random.nextInt(80);
        int shippedDaysAgo = deductionDaysAgo + 10 + random.nextInt(10);
        int receivedDays = deductionDaysAgo + 5;
        Asn asn = new Asn(shipped, shippedDaysAgo);
        Bol bol = new Bol(shipped, shippedDaysAgo);
        Pod pod = new Pod(shipped, receivedDays);
        switch (situation) {
            case SUPPORTS_RETAILER -> pod = new Pod(shipped - (1 + random.nextInt(9)), receivedDays);
            case EVIDENCE_MISSING -> {
                if (index % 2 == 0) {
                    bol = null;
                } else {
                    pod = null;
                }
            }
            case CONFLICT_ARBITRATED -> {
                asn = new Asn(shipped + 5 + random.nextInt(10), shippedDaysAgo);
                pod = new Pod(index < 4 ? shipped - 3 : shipped, receivedDays);
            }
            case CONFLICT_UNARBITRATED -> pod = new Pod(shipped + 2, receivedDays);
            case SUPPORTS_SUPPLIER -> {
            }
        }
        return Evidence.shipment(shipmentId, asn, bol, pod);
    }

    private static Evidence price(Situation situation, int index, Random random,
                                  int deductionDaysAgo, String sku, String invoiceNo) {
        int masterCents = 1000 + 50 * random.nextInt(20);
        BigDecimal master = money(masterCents);
        BigDecimal invoice = switch (situation) {
            case SUPPORTS_SUPPLIER -> index % 2 == 0 ? master : master.add(money(50));
            case SUPPORTS_RETAILER -> master.subtract(money(50));
            case CONFLICT_ARBITRATED -> index < 4 ? master.subtract(money(100)) : master;
            case CONFLICT_UNARBITRATED -> master.add(money(25));
            case EVIDENCE_MISSING -> master;
        };
        List<PriceInterval> intervals = new ArrayList<>();
        intervals.add(new PriceInterval(deductionDaysAgo + 30, null, master));
        if (situation == Situation.CONFLICT_ARBITRATED) {
            intervals.add(new PriceInterval(deductionDaysAgo + 90, deductionDaysAgo + 45,
                    master.add(money(100))));
        }
        if (situation == Situation.CONFLICT_UNARBITRATED) {
            intervals.add(new PriceInterval(deductionDaysAgo + 10, deductionDaysAgo - 5,
                    master.add(money(100))));
        }
        InvoiceLine line = situation == Situation.EVIDENCE_MISSING ? null
                : new InvoiceLine(10 + random.nextInt(20), invoice);
        return Evidence.price(sku, invoiceNo, intervals, line);
    }

    private static Evidence promotion(Situation situation, int index, Random random,
                                      int deductionDaysAgo, String sku, String docId) {
        BigDecimal accrued = money(50000 + random.nextInt(20) * 1000);
        BigDecimal settled = situation == Situation.SUPPORTS_RETAILER
                || (situation == Situation.CONFLICT_ARBITRATED && index < 4)
                ? accrued.subtract(money(10000 + random.nextInt(10000))) : accrued;
        List<Clause> clauses = new ArrayList<>();
        if (situation == Situation.CONFLICT_UNARBITRATED) {
            clauses.add(new Clause("3.1", SUPPORTING_CLAUSE, ClauseEffect.SUPPORTS_ACCRUAL));
            clauses.add(new Clause("3.2", DENYING_CLAUSE, ClauseEffect.DENIES_ACCRUAL));
            clauses.add(new Clause("3.3", SETTLEMENT_CLAUSE, ClauseEffect.NEUTRAL));
        } else {
            clauses.add(new Clause("3.1", SUPPORTING_CLAUSE, ClauseEffect.SUPPORTS_ACCRUAL));
            clauses.add(new Clause("3.2", situation == Situation.CONFLICT_ARBITRATED
                    ? CONFLICTING_CLAUSE : SETTLEMENT_CLAUSE,
                    situation == Situation.CONFLICT_ARBITRATED
                            ? ClauseEffect.CONFLICTS_WITH_SUMMARY : ClauseEffect.NEUTRAL));
        }
        Agreement agreement = new Agreement(docId, deductionDaysAgo + 60, null, List.copyOf(clauses));
        Settlement settlement = situation == Situation.EVIDENCE_MISSING ? null
                : new Settlement(docId, accrued, settled, deductionDaysAgo + 10);
        return Evidence.promotion(sku, List.of(agreement), settlement);
    }

    private static final String SUPPORTING_CLAUSE =
            "Retailer earns a four percent rebate on every unit purchased in the rebate window.";
    private static final String DENYING_CLAUSE =
            "No rebate is payable for units purchased in this window.";
    private static final String SETTLEMENT_CLAUSE =
            "The rebate is settled within thirty days after the rebate window closes.";
    private static final String CONFLICTING_CLAUSE =
            "Retailer earns a five percent rebate; the summary in the audit system understates it.";

    private static String shipmentId(int ordinal) {
        return "SHP-EVAL-" + String.format("%04d", ordinal);
    }

    private static String sku(int ordinal) {
        return "SKU-EVAL-" + String.format("%04d", ordinal);
    }

    private static String invoiceNo(int ordinal) {
        return "INV-EVAL-" + String.format("%04d", ordinal);
    }

    private static String docId(int ordinal) {
        return "PA-EVAL-" + String.format("%04d", ordinal);
    }

    private static int ordinal(DeductionCategory category, Situation situation, int index) {
        return 1 + (category.ordinal() * Situation.values().length + situation.ordinal())
                * CASES_PER_CELL + index;
    }

    private static BigDecimal money(int cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
