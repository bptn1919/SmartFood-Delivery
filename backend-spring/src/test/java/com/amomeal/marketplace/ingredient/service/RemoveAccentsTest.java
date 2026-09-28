package com.amomeal.marketplace.ingredient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Mirrors ../../backend/utils/functions/remove_accents.py::remove_accents. */
class RemoveAccentsTest {

    @Test
    void stripsVietnameseAccentsAndLowercases() {
        assertThat(RemoveAccents.apply("Cà Rốt")).isEqualTo("ca rot");
        assertThat(RemoveAccents.apply("Thịt Bò")).isEqualTo("thit bo");
        assertThat(RemoveAccents.apply("Đậu Phụ")).isEqualTo("dau phu");
    }

    @Test
    void leavesPlainAsciiUnchangedExceptCase() {
        assertThat(RemoveAccents.apply("Milk")).isEqualTo("milk");
    }
}
