package io.github.takahino.llmreviewer.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionDetectorTest {

    @Test
    void detectsExactMention() {
        assertTrue(MentionDetector.mentions("@reviewer-bot ここは大丈夫?", "reviewer-bot"));
    }

    @Test
    void detectsMentionInMiddleOfSentence() {
        assertTrue(MentionDetector.mentions("ありがとう @reviewer-bot、確認お願いします", "reviewer-bot"));
    }

    @Test
    void doesNotMatchDifferentUserWithSharedPrefix() {
        assertFalse(MentionDetector.mentions("@reviewer-bot-2 お願いします", "reviewer-bot"));
    }

    @Test
    void doesNotMatchWhenAtSignIsPrecededByUsernameChar() {
        assertFalse(MentionDetector.mentions("foo@reviewer-bot.example.com", "reviewer-bot"));
    }

    @Test
    void doesNotMatchWithoutAtSign() {
        assertFalse(MentionDetector.mentions("reviewer-bot お願いします", "reviewer-bot"));
    }

    @Test
    void isCaseSensitive() {
        assertFalse(MentionDetector.mentions("@Reviewer-Bot お願いします", "reviewer-bot"));
    }

    @Test
    void handlesPunctuationImmediatelyAfterMention() {
        assertTrue(MentionDetector.mentions("@reviewer-bot,お願いします", "reviewer-bot"));
    }

    @Test
    void returnsFalseForBlankInputs() {
        assertFalse(MentionDetector.mentions("", "reviewer-bot"));
        assertFalse(MentionDetector.mentions(null, "reviewer-bot"));
        assertFalse(MentionDetector.mentions("@reviewer-bot", ""));
    }
}
