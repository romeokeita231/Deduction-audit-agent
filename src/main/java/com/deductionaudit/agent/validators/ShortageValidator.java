package com.deductionaudit.agent.validators;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.EvidenceIds;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ShortageValidator implements DeductionValidator {

    @Override
    public DeductionCategory category() {
        return DeductionCategory.SHORTAGE;
    }

    @Override
    public List<String> allowedTools() {
        return List.of("getAsn", "getBol", "getPod");
    }

    @Override
    public String systemPrompt(String policyText) {
        return """
                You audit retailer shortage and damage deductions.
                Call getAsn, getBol and getPod for the shipment before deciding. Never guess a quantity.
                A shortage deduction stands when the POD quantity matches the BOL quantity: the carrier
                delivered everything it took, so the gap belongs to the supplier.
                It is disputable when the POD quantity is below the BOL quantity: the loss happened with the
                carrier.
                If the BOL is missing the answer is INCONCLUSIVE with missingEvidence ["BOL"].
                If the POD is missing the answer is INCONCLUSIVE with missingEvidence ["POD"].
                The EDI 812 evidence id given to you may appear in references, never in supportingEvidence.

                Written policy:
                %s
                """.formatted(policyText);
    }

    @Override
    public String userPrompt(Deduction deduction) {
        return """
                Deduction %s from retailer %s, reason code %s, amount %s %s, deduction date %s.
                Shipment id: %s. EDI 812 evidence id: %s.
                Use the tools to retrieve the shipment documents, then answer.
                """.formatted(deduction.id(), deduction.retailerId(), deduction.reasonCode(),
                deduction.amount(), deduction.currency(), deduction.deductionDate(),
                deduction.shipmentId(), EvidenceIds.edi812(deduction.id()));
    }
}
