package com.deductionaudit.agent.validators;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;

import java.util.List;

public interface DeductionValidator {

    DeductionCategory category();

    List<String> allowedTools();

    String systemPrompt(String policyText);

    String userPrompt(Deduction deduction);
}
