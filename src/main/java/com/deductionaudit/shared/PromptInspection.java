package com.deductionaudit.shared;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptInspection {

    private static final Pattern DEDUCTION_ID = Pattern.compile("EDI812:([0-9a-fA-F-]{36})");

    private PromptInspection() {
    }

    public static UUID deductionId(Prompt prompt) {
        for (Message message : prompt.getInstructions()) {
            Matcher matcher = DEDUCTION_ID.matcher(message.getText() == null ? "" : message.getText());
            if (matcher.find()) {
                return UUID.fromString(matcher.group(1));
            }
        }
        throw new IllegalStateException("the prompt carries no deduction id");
    }

    public static boolean isDraft(Prompt prompt) {
        for (Message message : prompt.getInstructions()) {
            if (message.getText() != null && message.getText().contains(PromptMarker.DRAFT)) {
                return true;
            }
        }
        return false;
    }
}
