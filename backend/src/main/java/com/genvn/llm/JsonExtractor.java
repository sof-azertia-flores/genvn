package com.genvn.llm;

/**
 * Models wrap JSON in prose or ``` fences more often than anyone would like.
 * Pull out the outermost balanced JSON object, ignoring braces inside strings.
 */
public final class JsonExtractor {

    private JsonExtractor() {}

    public static String extractObject(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.trim();
        }
        int start = s.indexOf('{');
        if (start < 0) return null;

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;
    }
}
