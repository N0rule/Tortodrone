package org.tortodrone.resources;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Bounded JSON data reader/writer; never interprets class names or executable values. */
public final class Json {
    private Json() { }
    public static Object parse(String text) throws IOException {
        return parse(text,32);
    }
    public static Object parse(String text,int maxDepth) throws IOException {
        if(maxDepth<1||maxDepth>128)throw new IllegalArgumentException("Invalid JSON depth limit");
        return parse(text,maxDepth,128);
    }
    public static Object parseCatalog(String text)throws IOException {return parse(text,32,50_000,262144);}
    private static Object parse(String text,int maxDepth,int maxFields)throws IOException {return parse(text,maxDepth,maxFields,65536);}
    public static Object parse(String text,int maxDepth,int maxFields,int maxString)throws IOException {
        if(maxDepth<1||maxDepth>128||maxFields<1||maxFields>50_000||maxString<1||maxString>4_000_000)throw new IllegalArgumentException("Invalid JSON limits");
        var parser = new Parser(text,maxDepth,maxFields,maxString);
        Object value = parser.value(0);
        parser.space();
        if (parser.at != text.length()) throw new IOException("Trailing JSON data");
        return value;
    }
    public static String stringify(Object value) throws IOException {
        return stringify(value,32);
    }
    public static String stringify(Object value,int maxDepth) throws IOException {
        if(maxDepth<1||maxDepth>128)throw new IllegalArgumentException("Invalid JSON depth limit");
        var out = new StringBuilder();
        write(out, value, 0,maxDepth);
        return out.toString();
    }
    private static void write(StringBuilder out, Object value, int depth,int maxDepth) throws IOException {
        if (depth > maxDepth) throw new IOException("JSON nesting exceeds limit");
        if (value == null) out.append("null");
        else if (value instanceof String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                    default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
                }
            }
            out.append('"');
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof BigDecimal) out.append(value);
        else if (value instanceof Float number && Float.isFinite(number)) out.append(number);
        else if (value instanceof Double number && Double.isFinite(number)) out.append(number);
        else if (value instanceof Map<?,?> map) {
            out.append('{'); boolean comma = false;
            var sorted = new TreeMap<String,Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IOException("Invalid JSON key");
                sorted.put(key, entry.getValue());
            }
            for (var entry : sorted.entrySet()) {
                if (comma) out.append(','); comma = true;
                write(out, entry.getKey(), depth+1,maxDepth); out.append(':'); write(out, entry.getValue(), depth+1,maxDepth);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('['); boolean comma = false;
            for (Object item : list) { if (comma) out.append(','); comma = true; write(out, item, depth+1,maxDepth); }
            out.append(']');
        } else throw new IOException("Unsupported JSON value");
    }
    private static final class Parser {
        private final String text; private final int maxDepth,maxFields,maxString; private int at, nodes;
        Parser(String text,int maxDepth,int maxFields,int maxString) { this.text = text;this.maxDepth=maxDepth;this.maxFields=maxFields;this.maxString=maxString; }
        void space() { while (at < text.length() && " \r\n\t".indexOf(text.charAt(at)) >= 0) at++; }
        IOException invalid() { return new IOException("Invalid JSON at offset " + at); }
        Object value(int depth) throws IOException {
            space();
            if (depth > maxDepth || ++nodes > 3_000_000 || at == text.length()) throw invalid();
            char c = text.charAt(at);
            if (c == '"') return string();
            if (c == '{') {
                at++; space(); var result = new LinkedHashMap<String,Object>();
                if (take('}')) return result;
                do {
                    space(); if (at == text.length() || text.charAt(at) != '"') throw invalid();
                    String key = string(); space(); if (!take(':') || result.containsKey(key)) throw invalid();
                    result.put(key, value(depth+1)); if (result.size() > maxFields) throw invalid(); space();
                    if (take('}')) return result;
                } while (take(','));
                throw invalid();
            }
            if (c == '[') {
                at++; space(); var result = new ArrayList<>();
                if (take(']')) return result;
                do {
                    result.add(value(depth+1)); if (result.size() > 50_000) throw invalid(); space();
                    if (take(']')) return result;
                } while (take(','));
                throw invalid();
            }
            for (String literal : List.of("true", "false", "null")) if (text.startsWith(literal, at)) {
                at += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
            int start = at;
            while (at < text.length() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            String number = text.substring(start, at);
            if (number.length() > 64 || !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
            try { return new BigDecimal(number); } catch (NumberFormatException bad) { throw invalid(); }
        }
        boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        String string() throws IOException {
            at++; var result = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    for (int i=0; i<result.length(); i++) {
                        char s=result.charAt(i);
                        if (Character.isHighSurrogate(s)) { if (++i==result.length() || !Character.isLowSurrogate(result.charAt(i))) throw invalid(); }
                        else if (Character.isLowSurrogate(s)) throw invalid();
                    }
                    return result.toString();
                }
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (at == text.length()) throw invalid();
                    c = text.charAt(at++);
                    c = switch (c) {
                        case '"', '\\', '/' -> c; case 'b' -> '\b'; case 'f' -> '\f';
                        case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> {
                            if (at+4 > text.length()) throw invalid();
                            String hex=text.substring(at,at+4); if (!hex.matches("[0-9a-fA-F]{4}")) throw invalid();
                            at+=4; yield (char)Integer.parseInt(hex,16);
                        }
                        default -> throw invalid();
                    };
                }
                result.append(c); if (result.length()>maxString) throw invalid();
            }
            throw invalid();
        }
    }
}
