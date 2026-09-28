package com.amomeal.marketplace.ingredient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the Java port of Python's difflib.SequenceMatcher.ratio() against
 * known reference outputs (computed with CPython's difflib for the same
 * inputs) — see SequenceMatcher class javadoc for why this exists (Django's
 * fuzzy-match fallback algorithm, reused for ingredient search/autocomplete).
 */
class SequenceMatcherTest {

    @Test
    void identicalStringsScoreOne() {
        assertThat(SequenceMatcher.ratio("ca rot", "ca rot")).isEqualTo(1.0);
    }

    @Test
    void bothEmptyScoresOne() {
        assertThat(SequenceMatcher.ratio("", "")).isEqualTo(1.0);
    }

    @Test
    void oneEmptyScoresZero() {
        assertThat(SequenceMatcher.ratio("", "abc")).isEqualTo(0.0);
    }

    @Test
    void completelyDifferentScoresZero() {
        assertThat(SequenceMatcher.ratio("abc", "xyz")).isEqualTo(0.0);
    }

    @Test
    void partialOverlapMatchesPythonReference() {
        // Reference values computed with CPython's difflib.SequenceMatcher (../../backend/venv):
        //   SequenceMatcher(None, "ca rot", "ca chua").ratio()   == 0.46153846153846156
        //   SequenceMatcher(None, "thit bo", "thit ga").ratio()  == 0.7142857142857143
        assertThat(SequenceMatcher.ratio("ca rot", "ca chua")).isCloseTo(0.46153846153846156, within(1e-9));
        assertThat(SequenceMatcher.ratio("thit bo", "thit ga")).isCloseTo(0.7142857142857143, within(1e-9));
    }
}
