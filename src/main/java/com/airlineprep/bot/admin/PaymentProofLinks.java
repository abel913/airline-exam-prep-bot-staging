package com.airlineprep.bot.admin;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts only explicit HTTP(S) links for the authenticated admin review page. */
final class PaymentProofLinks {
    private static final Pattern URL = Pattern.compile("(?i)(?<![A-Za-z0-9+.:/_-])https?://[^\\s<>\\\"']+");

    private PaymentProofLinks() {}

    static List<String> extract(String proof) {
        if (proof == null || proof.isBlank()) return List.of();
        Matcher matcher = URL.matcher(proof);
        LinkedHashSet<String> links = new LinkedHashSet<>();
        while (matcher.find()) {
            String candidate = trimSentencePunctuation(matcher.group());
            if (isSafeHttpUrl(candidate)) links.add(candidate);
        }
        return new ArrayList<>(links);
    }

    private static String trimSentencePunctuation(String value) {
        int end = value.length();
        while (end > 0 && ".,!?;:。".indexOf(value.charAt(end - 1)) >= 0) end--;
        String candidate = value.substring(0, end);
        while (candidate.endsWith(")") && count(candidate, ')') > count(candidate, '(')) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private static int count(String value, char sought) {
        int total = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == sought) total++;
        return total;
    }

    private static boolean isSafeHttpUrl(String value) {
        if (value.isBlank() || value.indexOf('\\') >= 0) return false;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
