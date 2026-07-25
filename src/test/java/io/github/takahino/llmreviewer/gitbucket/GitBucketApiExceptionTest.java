package io.github.takahino.llmreviewer.gitbucket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitBucketApiExceptionTest {

    @Test
    void messageOnlyConstructorDefaultsStatusCodeToMinusOne() {
        assertEquals(-1, new GitBucketApiException("failed").statusCode());
    }

    @Test
    void messageAndCauseConstructorDefaultsStatusCodeToMinusOne() {
        assertEquals(-1, new GitBucketApiException("failed", new RuntimeException()).statusCode());
    }

    @Test
    void statusCodeIsPreservedWhenProvided() {
        assertEquals(403, new GitBucketApiException("forbidden", 403).statusCode());
        assertEquals(500, new GitBucketApiException("server error", new RuntimeException(), 500).statusCode());
    }
}
