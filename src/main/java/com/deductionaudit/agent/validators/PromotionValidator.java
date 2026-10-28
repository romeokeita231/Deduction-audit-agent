package com.deductionaudit.agent.validators;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.EvidenceIds;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PromotionValidator implements DeductionValidator {

    @Override
    public DeductionCategory category() {
        return DeductionCategory.PROMOTION;
    }

    @Override
    public List<String> allowedTools() {
        return List.of("searchPromotionAgreements", "getPromotionSettlement");
    }

    @Override
    public String systemPrompt(String policyText) {
        return """
                You audit retailer promotion deductions.
                Use searchPromotionAgreements with a natural language query to find the agreement clauses,
                and getPromotionSettlement with the agreement document id to read the settlement record.
                The agreement clauses are the contract. When a clause disagrees with any summary in this
                prompt, the clause wins.
                When the accrued amount is greater than the settled amount and the agreement supports the
                accrual, the answer is DISPUTABLE.
                When the settled amount matches the agreement, the answer is DEDUCTION_STANDS.
                If the settlement record is missing the answer is INCONCLUSIVE with missingEvidence
                ["PROMOTION_SETTLEMENT"]. If no agreement applies the answer is INCONCLUSIVE with empty
                missingEvidence.
                Cite clauses in supportingEvidence using their clause evidence ids together with the
                settlement evidence id. The EDI 812 evidence id may appear in references, never in
                supportingEvidence.

                Written policy:
                %s
                """.formatted(policyText);
    }

    @Override
    public String userPrompt(Deduction deduction) {
        return """
                Deduction %s from retailer %s, reason code %s, amount %s %s, deduction date %s.
                SKU: %s. EDI 812 evidence id: %s.
                Use the tools to find the agreement clauses and the settlement record, then answer.
                """.formatted(deduction.id(), deduction.retailerId(), deduction.reasonCode(),
                deduction.amount(), deduction.currency(), deduction.deductionDate(),
                deduction.sku(), EvidenceIds.edi812(deduction.id()));
    }
}
