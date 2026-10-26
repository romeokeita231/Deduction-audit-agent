package com.deductionaudit.agent.validators;

import com.deductionaudit.domain.DeductionCategory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ValidatorRegistry {

    private final Map<DeductionCategory, DeductionValidator> validators = new EnumMap<>(DeductionCategory.class);

    public ValidatorRegistry(List<DeductionValidator> validators) {
        for (DeductionValidator validator : validators) {
            this.validators.put(validator.category(), validator);
        }
    }

    public DeductionValidator forCategory(DeductionCategory category) {
        DeductionValidator validator = validators.get(category);
        if (validator == null) {
            throw new IllegalStateException("no validator for category " + category);
        }
        return validator;
    }
}
