package com.deductionaudit.eval.corpus;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.eval.oracle.OracleExpectation;
import com.deductionaudit.persistence.EvidenceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class CorpusLoader {

    public static final String NAMESPACE = "deduction-audit/eval/v1:";

    private static final String LOCATION = "classpath:eval/corpus/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PathMatchingResourcePatternResolver resolver;

    public CorpusLoader(ResourceLoader resourceLoader) {
        this.resolver = new PathMatchingResourcePatternResolver(resourceLoader);
    }

    public List<LoadedCase> load(Clock clock) {
        Resource[] resources;
        try {
            resources = resolver.getResources(LOCATION + "*.json");
        } catch (IOException e) {
            throw new UncheckedIOException("the evaluation corpus cannot be listed", e);
        }
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename));
        List<LoadedCase> cases = new ArrayList<>(resources.length);
        for (Resource resource : resources) {
            cases.add(resolve(read(resource), clock));
        }
        return List.copyOf(cases);
    }

    public static UUID deductionId(String caseId) {
        return UUID.nameUUIDFromBytes((NAMESPACE + caseId).getBytes(StandardCharsets.UTF_8));
    }

    private static CorpusEntry read(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            CorpusEntry entry = JSON.readValue(stream, CorpusEntry.class);
            if (entry.facts() == null || entry.expectation() == null
                    || entry.facts().caseId() == null || entry.facts().cell() == null
                    || entry.facts().cell().category() == null || entry.facts().cell().situation() == null) {
                throw new IllegalStateException("corpus case " + resource.getFilename()
                        + " is missing its caseId, cell or expectation");
            }
            return entry;
        } catch (IOException e) {
            throw new UncheckedIOException("corpus case " + resource.getFilename() + " cannot be read", e);
        }
    }

    private static LoadedCase resolve(CorpusEntry entry, Clock clock) {
        CorpusCase facts = entry.facts();
        CorpusCase.Evidence evidence = facts.evidence();
        Instant now = clock.instant();
        LocalDate today = today(clock);
        LocalDate deductionDate = today.minusDays(facts.deductionDaysAgo());
        Instant receivedAt = now.minus(Duration.ofDays(facts.receivedDaysAgo()));
        LocalDate windowEndsAt = deductionDate.plusDays(facts.windowDays());

        Deduction deduction = new Deduction(
                deductionId(facts.caseId()), facts.retailerId(), facts.reasonCode(),
                facts.cell().category(), facts.amount(), facts.currency(), deductionDate,
                receivedAt, windowEndsAt, DeductionStatus.NEW, null, null,
                evidence.invoiceNo(), null, evidence.shipmentId(), evidence.sku(), 0);

        EvidenceStore.AsnRow asn = evidence.asn() == null ? null
                : new EvidenceStore.AsnRow(evidence.shipmentId(), evidence.asn().declaredQty(),
                now.minus(Duration.ofDays(evidence.asn().shippedDaysAgo())), null);
        EvidenceStore.BolRow bol = evidence.bol() == null ? null
                : new EvidenceStore.BolRow(evidence.shipmentId(), evidence.bol().shippedQty(),
                "XPO", now.minus(Duration.ofDays(evidence.bol().shippedDaysAgo())));
        EvidenceStore.PodRow pod = evidence.pod() == null ? null
                : new EvidenceStore.PodRow(evidence.shipmentId(), evidence.pod().receivedQty(),
                now.minus(Duration.ofDays(evidence.pod().receivedDaysAgo())), "dock-1");

        List<EvidenceStore.PriceRow> prices = new ArrayList<>();
        for (CorpusCase.PriceInterval interval : evidence.priceIntervals()) {
            prices.add(new EvidenceStore.PriceRow(evidence.sku(),
                    today.minusDays(interval.validFromDaysAgo()),
                    interval.validToDaysAgo() == null ? null : today.minusDays(interval.validToDaysAgo()),
                    interval.unitPrice()));
        }

        EvidenceStore.InvoiceRow invoice = evidence.invoiceLine() == null ? null
                : new EvidenceStore.InvoiceRow(evidence.invoiceNo(), evidence.sku(),
                evidence.invoiceLine().qty(), evidence.invoiceLine().unitPrice());

        List<LoadedCase.Agreement> agreements = new ArrayList<>();
        for (CorpusCase.Agreement agreement : evidence.agreements()) {
            List<EvidenceStore.ClauseRow> clauses = new ArrayList<>();
            for (CorpusCase.Clause clause : agreement.clauses()) {
                clauses.add(new EvidenceStore.ClauseRow(agreement.docId(), clause.clauseNo(),
                        clause.text(), 0.0));
            }
            agreements.add(new LoadedCase.Agreement(agreement.docId(),
                    "Synthetic evaluation agreement " + agreement.docId(), facts.retailerId(), evidence.sku(),
                    today.minusDays(agreement.validFromDaysAgo()),
                    agreement.validToDaysAgo() == null ? null : today.minusDays(agreement.validToDaysAgo()),
                    List.copyOf(clauses)));
        }

        EvidenceStore.SettlementRow settlement = evidence.settlement() == null ? null
                : new EvidenceStore.SettlementRow(evidence.settlement().docId(),
                evidence.settlement().accruedAmount(), evidence.settlement().settledAmount(),
                today.minusDays(evidence.settlement().settledDaysAgo()));

        return new LoadedCase(facts, entry.expectation(), deduction, asn, bol, pod, List.copyOf(prices),
                invoice, List.copyOf(agreements), settlement);
    }

    private static LocalDate today(Clock clock) {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    public record LoadedCase(
            CorpusCase facts,
            OracleExpectation expectation,
            Deduction deduction,
            EvidenceStore.AsnRow asn,
            EvidenceStore.BolRow bol,
            EvidenceStore.PodRow pod,
            List<EvidenceStore.PriceRow> prices,
            EvidenceStore.InvoiceRow invoice,
            List<Agreement> agreements,
            EvidenceStore.SettlementRow settlement) {

        public record Agreement(String docId, String title, String retailerId, String sku,
                                LocalDate validFrom, LocalDate validTo,
                                List<EvidenceStore.ClauseRow> clauses) {
        }
    }
}
