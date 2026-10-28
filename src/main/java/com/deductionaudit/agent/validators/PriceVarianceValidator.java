package com.deductionaudit.agent.validators;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.EvidenceIds;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PriceVarianceValidator implements DeductionValidator {

    @Override
    public DeductionCategory category() {
        return DeductionCategory.PRICE_VARIANCE;
    }

    @Override
    public List<String> allowedTools() {
        return List.of("getPriceMaster", "getInvoiceLine");
    }

    @Override
    public String systemPrompt(String policyText) {
        return """
                You audit retailer price variance deductions.
                Call getPriceMaster for the SKU and deduction date, and getInvoiceLine for the invoice,
                before deciding. Only the price master interval that contains the deduction date is
                authoritative; never use another interval.
                When the invoice unit price is below the price master unit price, the deduction is not
                supported: the answer is DISPUTABLE.
                When the invoice unit price matches or is above the price master unit price, the answer is
                DEDUCTION_STANDS.
                If the invoice line is missing the answer is INCONCLUSIVE with missingEvidence
                ["INVOICE_LINE"]. If no price interval covers the deduction date the answer is INCONCLUSIVE
                with empty missingEvidence.
                The EDI 812 evidence id given to you may appear in references, never in supportingEvidence.

                Written policy:
                %s
                """.formatted(policyText);
    }

    @Override
    public String userPrompt(Deduction deduction) {
        return """
                Deduction %s from retailer %s, reason code %s, amount %s %s, deduction date %s.
                SKU: %s. Invoice number: %s. EDI 812 evidence id: %s.
                Use the tools to retrieve the invoice line and the price master, then answer.
                """.formatted(deduction.id(), deduction.retailerId(), deduction.reasonCode(),
                deduction.amount(), deduction.currency(), deduction.deductionDate(),
                deduction.sku(), deduction.invoiceNo(), EvidenceIds.edi812(deduction.id()));
    }
}
