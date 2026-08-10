package com.leantpm.common.excel;

import java.util.List;
import java.util.Set;

public final class ImportWorkbookSupport {
    private ImportWorkbookSupport() {
    }

    public static String displayHeader(String canonicalHeader, Set<String> requiredHeaders) {
        return requiredHeaders.contains(canonicalHeader)
                ? "*" + canonicalHeader : canonicalHeader;
    }

    public static List<String> displayHeaders(
            List<String> canonicalHeaders,
            Set<String> requiredHeaders
    ) {
        return canonicalHeaders.stream()
                .map(header -> displayHeader(header, requiredHeaders))
                .toList();
    }

    public static String canonicalHeader(String displayedHeader) {
        if (displayedHeader == null) {
            return null;
        }
        String normalized = displayedHeader.trim();
        while (normalized.startsWith("*") || normalized.startsWith("＊")) {
            normalized = normalized.substring(1).trim();
        }
        while (normalized.endsWith("*") || normalized.endsWith("＊")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }
}
