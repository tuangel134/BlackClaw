package com.blackclaw.android.utils;

import java.util.Locale;

/**
 * Shared normalization rules for matching UI text exposed through Android accessibility.
 *
 * This is intentionally deterministic, but more tolerant than raw platform text search:
 * - ignores case and punctuation differences
 * - tolerates extra words around the target text
 * - can match phone-like digit strings across formatting differences
 */
public final class UiTextMatchUtils {
    private UiTextMatchUtils() {}

    public static String normalizeText(String value) {
        if (value == null) return "";
        return value
                .trim()
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    public static String digitsOnly(String value) {
        if (value == null) return "";
        return value.replaceAll("\\D+", "");
    }

    public static boolean matchesExactOrNormalized(CharSequence candidate, String query) {
        return matchesCandidate(candidate != null ? candidate.toString() : null, query, true);
    }

    public static boolean matchesRelaxed(CharSequence candidate, String query) {
        return matchesCandidate(candidate != null ? candidate.toString() : null, query, false);
    }

    private static boolean matchesCandidate(String candidate, String query, boolean exactOnly) {
        if (candidate == null || query == null) return false;

        String trimmedCandidate = candidate.trim();
        String trimmedQuery = query.trim();
        if (trimmedCandidate.isEmpty() || trimmedQuery.isEmpty()) return false;

        if (trimmedCandidate.equalsIgnoreCase(trimmedQuery)) {
            return true;
        }

        String normalizedCandidate = normalizeText(trimmedCandidate);
        String normalizedQuery = normalizeText(trimmedQuery);
        if (normalizedCandidate.isEmpty() || normalizedQuery.isEmpty()) {
            return false;
        }

        if (normalizedCandidate.equals(normalizedQuery)) {
            return true;
        }

        String candidateDigits = digitsOnly(trimmedCandidate);
        String queryDigits = digitsOnly(trimmedQuery);
        if (!candidateDigits.isEmpty() && queryDigits.length() >= 4) {
            if (candidateDigits.equals(queryDigits)) {
                return true;
            }
            if (!exactOnly && candidateDigits.contains(queryDigits)) {
                return true;
            }
        }

        if (exactOnly) {
            return false;
        }

        if (normalizedQuery.length() >= 3 && normalizedCandidate.contains(normalizedQuery)) {
            return true;
        }

        return false;
    }
}
