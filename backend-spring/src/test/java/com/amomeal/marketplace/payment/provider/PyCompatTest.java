package com.amomeal.marketplace.payment.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each expectation is what CPython 3.12 prints for the same value. */
class PyCompatTest {

    @Test
    void str_matchesPython() {
        assertThat(PyCompat.str(null)).isEqualTo("None");
        assertThat(PyCompat.str(true)).isEqualTo("True");
        assertThat(PyCompat.str(false)).isEqualTo("False");
        assertThat(PyCompat.str(2000)).isEqualTo("2000");
        assertThat(PyCompat.str(9_000_000_000L)).isEqualTo("9000000000");
        assertThat(PyCompat.str(1.5)).isEqualTo("1.5");
        assertThat(PyCompat.str(2000.0)).isEqualTo("2000.0");
        assertThat(PyCompat.str("x")).isEqualTo("x");
    }

    @Test
    void floatRepr_matchesPythonRepr() {
        assertThat(PyCompat.floatRepr(10000000.0)).isEqualTo("10000000.0");
        assertThat(PyCompat.floatRepr(0.1)).isEqualTo("0.1");
        assertThat(PyCompat.floatRepr(1e16)).isEqualTo("1e+16");
        assertThat(PyCompat.floatRepr(1.5e16)).isEqualTo("1.5e+16");
        assertThat(PyCompat.floatRepr(1e-5)).isEqualTo("1e-05");
        assertThat(PyCompat.floatRepr(0.0001)).isEqualTo("0.0001");
        assertThat(PyCompat.floatRepr(-2.5)).isEqualTo("-2.5");
        assertThat(PyCompat.floatRepr(0.0)).isEqualTo("0.0");
    }

    @Test
    void repr_ofDicts_matchesPythonStrOfAWebhookPayload() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", 1);
        data.put("ok", true);
        data.put("n", null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "00");
        payload.put("data", data);
        payload.put("s", "it's");
        // V10 (Python): str({...})
        assertThat(PyCompat.str(payload))
                .isEqualTo("{'code': '00', 'data': {'orderCode': 1, 'ok': True, 'n': None}, 's': \"it's\"}");
        assertThat(PyCompat.repr(List.of("a", 1))).isEqualTo("['a', 1]");
    }

    @Test
    void decimal_isScalePreserving_likePythonStrDecimal() {
        assertThat(PyCompat.decimal(new BigDecimal("180000"))).isEqualTo("180000");
        assertThat(PyCompat.decimal(new BigDecimal("180000.00"))).isEqualTo("180000.00");
        assertThat(PyCompat.decimal(BigDecimal.ZERO)).isEqualTo("0");
        assertThat(PyCompat.decimal(new BigDecimal("0.00"))).isEqualTo("0.00");
    }

    @Test
    void jsonDumpsCompact_matchesPythonJsonDumpsWithEnsureAsciiFalse() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", "Việt \"q\"\n");
        m.put("b", List.of(1, 2.0, true));
        m.put("c", null);
        assertThat(PyCompat.jsonDumpsCompact(m)).isEqualTo("{\"a\":\"Việt \\\"q\\\"\\n\",\"b\":[1,2.0,true],\"c\":null}");
    }

    @Test
    void quote_matchesUrllibQuoteWithDefaultSafeSlash() {
        assertThat(PyCompat.quote("a b/c:d+e~f_g.h-i")).isEqualTo("a%20b/c%3Ad%2Be~f_g.h-i");
        assertThat(PyCompat.quote("Ă")).isEqualTo("%C4%82");
        assertThat(PyCompat.quote("[\"x\"]")).isEqualTo("%5B%22x%22%5D");
    }

    @Test
    void toLong_followsPythonIntCoercion() {
        assertThat(PyCompat.toLong(12)).isEqualTo(12L);
        assertThat(PyCompat.toLong(" 42 ")).isEqualTo(42L);
        assertThat(PyCompat.toLong("+7")).isEqualTo(7L);
        assertThat(PyCompat.toLong(12.9)).isEqualTo(12L);
        assertThatThrownBy(() -> PyCompat.toLong("abc")).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> PyCompat.toLong(null)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void equalsTrue_andTruthiness_followPython() {
        assertThat(PyCompat.equalsTrue(true)).isTrue();
        assertThat(PyCompat.equalsTrue(1)).isTrue();        // 1 == True in Python
        assertThat(PyCompat.equalsTrue(1.0)).isTrue();
        assertThat(PyCompat.equalsTrue("true")).isFalse();
        assertThat(PyCompat.equalsTrue(false)).isFalse();
        assertThat(PyCompat.truthy(Map.of())).isFalse();
        assertThat(PyCompat.truthy("")).isFalse();
        assertThat(PyCompat.truthy(0)).isFalse();
        assertThat(PyCompat.truthy(List.of(1))).isTrue();
    }

    @Test
    void isoformat_matchesPythonAwareDatetimeIsoformat() {
        assertThat(PyCompat.isoformat(Instant.parse("2026-09-23T10:11:12.123456Z"))).isEqualTo("2026-09-23T10:11:12.123456+00:00");
        assertThat(PyCompat.isoformat(Instant.parse("2026-09-23T10:11:12Z"))).isEqualTo("2026-09-23T10:11:12+00:00");
        assertThat(PyCompat.isoformat(null)).isNull();
    }
}
