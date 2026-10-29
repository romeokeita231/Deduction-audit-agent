package com.deductionaudit.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record DisputeDocument(
        Claim claim,
        String narrative,
        JsonNode digest,
        int evidenceVersion,
        String promptVersion,
        String model) {
}
