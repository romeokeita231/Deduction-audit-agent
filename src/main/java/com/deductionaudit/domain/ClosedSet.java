package com.deductionaudit.domain;

import java.util.List;
import java.util.Set;

public final class ClosedSet {

    private ClosedSet() {
    }

    public static void assertClosedSet(List<String> references,
                                       List<String> supportingEvidence,
                                       Set<String> idsReturnedByRun,
                                       String ownEdi812Id) {
        if (supportingEvidence != null) {
            for (String id : supportingEvidence) {
                if (id == null || !idsReturnedByRun.contains(id)) {
                    throw new ClosedSetViolation("supportingEvidence id was not returned by a tool: " + id);
                }
            }
        }
        if (references != null) {
            for (String id : references) {
                boolean allowed = (ownEdi812Id != null && ownEdi812Id.equals(id)) || idsReturnedByRun.contains(id);
                if (!allowed) {
                    throw new ClosedSetViolation("reference id was not returned by a tool: " + id);
                }
            }
        }
        if (supportingEvidence != null && ownEdi812Id != null && supportingEvidence.contains(ownEdi812Id)) {
            throw new ClosedSetViolation("the EDI 812 belongs in references, not supportingEvidence");
        }
    }

    public static final class ClosedSetViolation extends RuntimeException {
        public ClosedSetViolation(String message) {
            super(message);
        }
    }
}
