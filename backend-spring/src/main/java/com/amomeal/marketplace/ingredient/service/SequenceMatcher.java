package com.amomeal.marketplace.ingredient.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java re-implementation of Python's {@code difflib.SequenceMatcher.ratio()}
 * (Ratcliff/Obershelp gestalt pattern matching) — used throughout
 * ../../backend/ingredient/orm/ingredient.py and services/__init__.py for
 * fuzzy-matching ingredient names/aliases (e.g.
 * {@code SequenceMatcher(None, query.lower(), name.lower()).ratio()}).
 * No junk-element filtering/autojunk (Django's call sites never pass an
 * {@code isjunk} callback, and autojunk only ever affects sequences of
 * length &gt;= 200, which ingredient names/aliases never reach) — this is a
 * faithful, simplified port for the actual inputs this module sees.
 */
public final class SequenceMatcher {

    private SequenceMatcher() {
    }

    public static double ratio(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int matches = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[]{0, a.length(), 0, b.length()});
        Map<Character, List<Integer>> b2j = buildB2j(b);
        while (!queue.isEmpty()) {
            int[] range = queue.pop();
            int alo = range[0], ahi = range[1], blo = range[2], bhi = range[3];
            int[] match = findLongestMatch(a, b, alo, ahi, blo, bhi, b2j);
            int i = match[0], j = match[1], k = match[2];
            if (k > 0) {
                matches += k;
                if (alo < i && blo < j) {
                    queue.push(new int[]{alo, i, blo, j});
                }
                if (i + k < ahi && j + k < bhi) {
                    queue.push(new int[]{i + k, ahi, j + k, bhi});
                }
            }
        }
        return (2.0 * matches) / (a.length() + b.length());
    }

    private static Map<Character, List<Integer>> buildB2j(String b) {
        Map<Character, List<Integer>> b2j = new HashMap<>();
        for (int i = 0; i < b.length(); i++) {
            b2j.computeIfAbsent(b.charAt(i), c -> new ArrayList<>()).add(i);
        }
        return b2j;
    }

    private static int[] findLongestMatch(String a, String b, int alo, int ahi, int blo, int bhi,
                                           Map<Character, List<Integer>> b2j) {
        int besti = alo, bestj = blo, bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newJ2Len = new HashMap<>();
            List<Integer> indices = b2j.get(a.charAt(i));
            if (indices != null) {
                for (int j : indices) {
                    if (j < blo) continue;
                    if (j >= bhi) break;
                    int k = j2len.getOrDefault(j - 1, 0) + 1;
                    newJ2Len.put(j, k);
                    if (k > bestsize) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        bestsize = k;
                    }
                }
            }
            j2len = newJ2Len;
        }
        return new int[]{besti, bestj, bestsize};
    }
}
