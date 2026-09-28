package com.amomeal.marketplace.payment.provider;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The exact Python string conversions the payment module's HMACs are computed over.
 *
 * <p><b>Why this class exists.</b> Every signature in {@code payment} — PayOS request/webhook
 * signatures, the PayOS SDK's payout signature, the wallet balance signature and both hash
 * chains — is an HMAC over a string that Python built with {@code f"{value}"},
 * {@code str(value)}, {@code json.dumps(...)} or {@code urllib.parse.quote(...)}. A Java port
 * that formats any value even slightly differently (e.g. {@code true} vs {@code True},
 * {@code 1.0E7} vs {@code 10000000.0}, {@code "180000"} vs {@code "180000.00"}) silently
 * produces a different signature. Each method below is a narrow, tested re-implementation of
 * one Python conversion; {@code PyCompatTest}/{@code PayOsSignatureTest} pin them against
 * vectors produced by running the real Python code in {@code ../backend/venv}.
 */
public final class PyCompat {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PyCompat() {
    }

    // =====================================================================
    // str()
    // =====================================================================

    /**
     * Python {@code str(value)} / {@code f"{value}"} for JSON-decoded values: {@code None},
     * {@code True}/{@code False}, int, float (repr), str, and {@code repr()} of list/dict.
     */
    public static String str(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof Double || value instanceof Float) {
            return floatRepr(((Number) value).doubleValue());
        }
        if (value instanceof BigDecimal bd) {
            return decimal(bd);
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return repr(value);
        }
        return value.toString();
    }

    /** Python {@code repr()} of a JSON-decoded value (dict/list/str with Python quoting). */
    public static String repr(Object value) {
        if (value instanceof String s) {
            return stringRepr(s);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
                if (it.hasNext()) {
                    sb.append(", ");
                }
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(repr(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return str(value);
    }

    /** Python {@code repr(str)}: single quotes unless the text has a ' and no ". */
    static String stringRepr(String s) {
        char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(quote);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == quote || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                sb.append(String.format("\\x%02x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append(quote).toString();
    }

    /**
     * Python {@code repr(float)}: shortest round-trip digits, always with a decimal point or
     * exponent, exponent form ({@code 1e+16}) only outside [1e-4, 1e16).
     */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0) {
            return (1 / d < 0) ? "-0.0" : "0.0";
        }
        BigDecimal shortest = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        double abs = Math.abs(d);
        if (abs >= 1e16 || abs < 1e-4) {
            // Python: mantissa digits + 'e' + sign + at-least-2-digit exponent
            String unscaled = shortest.unscaledValue().abs().toString();
            int exponent = unscaled.length() - 1 - shortest.scale();
            String mantissa = unscaled.length() == 1 ? unscaled : unscaled.charAt(0) + "." + unscaled.substring(1);
            String sign = d < 0 ? "-" : "";
            String expSign = exponent < 0 ? "-" : "+";
            return sign + mantissa + "e" + expSign + String.format("%02d", Math.abs(exponent));
        }
        String plain = shortest.toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    /**
     * Python {@code str(Decimal)} for the plain (non-exponent) values the payment code
     * handles. <b>Scale-preserving on purpose</b>: {@code Decimal("180000")} prints
     * {@code 180000} while {@code Decimal("180000.00")} (a NUMERIC(15,2) read back from
     * Postgres) prints {@code 180000.00} — Django's wallet signature and ledger chain depend
     * on exactly that difference (see PROGRESS.md, payment open questions).
     */
    public static String decimal(BigDecimal value) {
        return value.toPlainString();
    }

    // =====================================================================
    // json.dumps(..., separators=(",", ":"), ensure_ascii=False)
    // =====================================================================

    /** Compact Python {@code json.dumps} with {@code ensure_ascii=False}, preserving map order. */
    public static String jsonDumpsCompact(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeJsonString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            sb.append(Double.isNaN(d) ? "NaN" : Double.isInfinite(d) ? (d > 0 ? "Infinity" : "-Infinity") : floatRepr(d));
        } else if (value instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeJson(sb, list.get(i));
            }
            sb.append(']');
        } else {
            writeJsonString(sb, value.toString());
        }
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** The PayOS SDK's {@code deep_sort_object(obj, sort_arrays=False)}: dict keys sorted recursively, list order kept. */
    public static Object deepSort(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), deepSort(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PyCompat::deepSort).toList();
        }
        return value;
    }

    // =====================================================================
    // urllib.parse.quote(value)  (safe='/')
    // =====================================================================

    /** Python {@code urllib.parse.quote(s)} with the default {@code safe='/'}. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
            }
        }
        return sb.toString();
    }

    // =====================================================================
    // misc
    // =====================================================================

    /** Python {@code int(x)} for the order-code coercions Django performs (int/str/float). */
    public static long toLong(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger bi) {
            return bi.longValueExact();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException("cannot convert float " + d + " to integer");
            }
            return (long) d; // Python int(float) truncates toward zero
        }
        if (value instanceof String s) {
            return Long.parseLong(s.strip().replace("_", ""));
        }
        throw new NumberFormatException("int() argument must be a string or a number, not " + value);
    }

    /** Python {@code value == True}: also true for {@code 1} and {@code 1.0}. */
    public static boolean equalsTrue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() == 1.0;
        }
        return false;
    }

    /** Python's {@code bool(x)} truthiness for a JSON-decoded value. */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof List<?> l) {
            return !l.isEmpty();
        }
        return true;
    }

    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** Python {@code aware_datetime.isoformat()} in UTC ({@code USE_TZ=True, TIME_ZONE='UTC'}). */
    public static String isoformat(Instant instant) {
        if (instant == null) {
            return null;
        }
        var odt = instant.atOffset(ZoneOffset.UTC);
        return (odt.getNano() / 1000 == 0 ? ISO_SECONDS : ISO_MICROS).format(odt);
    }
}
