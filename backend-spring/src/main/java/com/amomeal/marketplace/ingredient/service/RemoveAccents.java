package com.amomeal.marketplace.ingredient.service;

import java.util.regex.Pattern;

/**
 * Mirrors ../../backend/utils/functions/remove_accents.py::remove_accents
 * exactly (same character classes, same order: lowercase first, then map
 * each accented Vietnamese vowel/đ to its base Latin letter, then strip any
 * leftover combining diacritical marks).
 */
public final class RemoveAccents {

    private static final Pattern A = Pattern.compile("[àáạảãâầấậẩẫăằắặẳẵ]");
    private static final Pattern E = Pattern.compile("[èéẹẻẽêềếệểễ]");
    private static final Pattern I = Pattern.compile("[ìíịỉĩ]");
    private static final Pattern O = Pattern.compile("[òóọỏõôồốộổỗơờớợởỡ]");
    private static final Pattern U = Pattern.compile("[ùúụủũưừứựửữ]");
    private static final Pattern Y = Pattern.compile("[ỳýỵỷỹ]");
    private static final Pattern D = Pattern.compile("[đ]");
    private static final Pattern COMBINING_1 = Pattern.compile("[̣̀́̃̉]");
    private static final Pattern COMBINING_2 = Pattern.compile("[ˆ̛̆]");

    private RemoveAccents() {
    }

    public static String apply(String text) {
        if (text == null) {
            return null;
        }
        String result = text.toLowerCase();
        result = A.matcher(result).replaceAll("a");
        result = E.matcher(result).replaceAll("e");
        result = I.matcher(result).replaceAll("i");
        result = O.matcher(result).replaceAll("o");
        result = U.matcher(result).replaceAll("u");
        result = Y.matcher(result).replaceAll("y");
        result = D.matcher(result).replaceAll("d");
        result = COMBINING_1.matcher(result).replaceAll("");
        result = COMBINING_2.matcher(result).replaceAll("");
        return result;
    }
}
