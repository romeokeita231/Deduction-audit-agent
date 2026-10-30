package com.deductionaudit.eval.corpus;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionStatus;
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
    private static final String PRICE_OFFSET = "@";
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

    public static String resolveEvidence(String token, Clock clock) {
        if (!token.startsWith("PRICE:")) {
            return token;
        }
        int at = token.lastIndexOf(PRICE_OFFSET);
        if (at < 0) {
            return token;
        }
        int daysAgo = Integer.parseInt(token.substring(at + 1));
        LocalDate date = today(clock).minusDays(daysAgo);
        return token.substring(0, at) + date;
    }

    private static CorpusCase read(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            CorpusCase facts = JSON.readValue(stream, CorpusCase.class);
            if (facts.caseId() == null || facts.matrix() == null
                    || facts.matrix().category() == null || facts.matrix().situation() == null) {
                throw new IllegalStateException("corpus case " + resource.getFilename()
                        + " is missing its caseId or matrix");
            }
            return facts;
        } catch (IOException e) {
            throw new UncheckedIOException("corpus case " + resource.getFilename() + " cannot be read", e);
        }
    }

    private static LoadedCase resolve(CorpusCase facts, Clock clock) {
        Instant now = clock.instant();
        LocalDate today = today(clock);
        LocalDate deductionDate = today.minusDays(facts.deductionDaysAgo());
        Instant receivedAt = now.minus(Duration.ofDays(facts.receivedDaysAgo()));
        LocalDate windowEndsAt = deductionDate.plusDays(facts.windowDays());

        Deduction deduction = new Deduction(
                deductionId(facts.caseId()), facts.retailerId(), facts.reasonCode(),
                facts.matrix().category(), facts.amount(), facts.currency(), deductionDate,
                receivedAt, windowEndsAt, DeductionStatus.NEW, null, null,
                facts.invoiceNo(), null, facts.shipmentId(), facts.sku(), 0);

        EvidenceStore.AsnRow asn = facts.asn() == null ? null
                : new EvidenceStore.AsnRow(facts.shipmentId(), facts.asn().declaredQty(),
                now.minus(Duration.ofDays(facts.asn().shippedDaysAgo())), null);
        EvidenceStore.BolRow bol = facts.bol() == null ? null
                : new EvidenceStore.BolRow(facts.shipmentId(), facts.bol().shippedQty(),
                "XPO", now.minus(Duration.ofDays(facts.bol().shippedDaysAgo())));
        EvidenceStore.PodRow pod = facts.pod() == null ? null
                : new EvidenceStore.PodRow(facts.shipmentId(), facts.pod().receivedQty(),
                now.minus(Duration.ofDays(facts.pod().receivedDaysAgo())), "dock-1");

        List<EvidenceStore.PriceRow> prices = new ArrayList<>();
        for (CorpusCase.PriceInterval interval : facts.priceIntervals()) {
            prices.add(new EvidenceStore.PriceRow(facts.sku(),
                    today.minusDays(interval.validFromDaysAgo()),
                    interval.validToDaysAgo() == null ? null : today.minusDays(interval.validToDaysAgo()),
                    interval.unitPrice()));
        }

        EvidenceStore.InvoiceRow invoice = facts.invoiceLine() == null ? null
                : new EvidenceStore.InvoiceRow(facts.invoiceNo(), facts.sku(),
                facts.invoiceLine().qty(), facts.invoiceLine().unitPrice());

        List<LoadedCase.Agreement> agreements = new ArrayList<>();
        for (CorpusCase.Agreement agreement : facts.agreements()) {
            List<EvidenceStore.ClauseRow> clauses = new ArrayList<>();
            for (CorpusCase.Clause clause : agreement.clauses()) {
                clauses.add(new EvidenceStore.ClauseRow(agreement.docId(), clause.clauseNo(),
                        clause.text(), 0.0));
            }
            agreements.add(new LoadedCase.Agreement(agreement.docId(),
                    "Synthetic evaluation agreement " + agreement.docId(), facts.retailerId(), facts.sku(),
                    today.minusDays(agreement.validFromDaysAgo()),
                    agreement.validToDaysAgo() == null ? null : today.minusDays(agreement.validToDaysAgo()),
                    List.copyOf(clauses)));
        }

        EvidenceStore.SettlementRow settlement = facts.settlement() == null ? null
                : new EvidenceStore.SettlementRow(facts.settlement().docId(),
                facts.settlement().accruedAmount(), facts.settlement().settledAmount(),
                today.minusDays(facts.settlement().settledDaysAgo()));

        return new LoadedCase(facts, deduction, asn, bol, pod, List.copyOf(prices), invoice,
                List.copyOf(agreements), settlement);
    }

    private static LocalDate today(Clock clock) {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    public record LoadedCase(
            CorpusCase facts,
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
