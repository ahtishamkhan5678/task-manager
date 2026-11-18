package util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer with no external libraries.
 * Stands in for the instructor-provided SimpleJson helper.
 *
 * Reading produces: Map (object), List (array), String, Long/Double (number), Boolean, or null.
 */
public final class SimpleJson {

    private SimpleJson() {
    }

    /** Parses any JSON value. Throws IllegalArgumentException on malformed input. */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON text is null");
        }
        Reader reader = new Reader(text);
        reader.skipWhitespace();
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.pos != text.length()) {
            throw new IllegalArgumentException("Unexpected trailing characters at position " + reader.pos);
        }
        return value;
    }

    /** Parses text that must be a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("JSON value is not an object");
        }
        return (Map<String, Object>) value;
    }

    /** Compact JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, -1, 0);
        return sb.toString();
    }

    /** Indented JSON (2 spaces), used for printing responses. */
    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 2, 0);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb, int indent, int level) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(s, sb);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Enum<?> e) {
            quote(e.name(), sb);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent, level + 1);
                quote(String.valueOf(entry.getKey()), sb);
                sb.append(indent >= 0 ? ": " : ":");
                write(entry.getValue(), sb, indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append('}');
        } else if (value instanceof Collection<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent, level + 1);
                write(item, sb, indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append(']');
        } else {
            quote(value.toString(), sb);
        }
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) {
            return;
        }
        sb.append('\n');
        sb.append(" ".repeat(indent * level));
    }

    private static void quote(String s, StringBuilder sb) {
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

    /** Recursive-descent reader. */
    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("Unexpected end of input");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            if (c == '-' || Character.isDigit(c)) {
                return readNumber();
            }
            throw error("Unexpected character '" + c + "'");
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected a string key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("Bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String number = text.substring(start, pos);
            try {
                if (number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException ex) {
                throw error("Bad number '" + number + "'");
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("Unexpected end of input");
            }
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw error("Expected '" + expected + "' but found '" + c + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " (position " + pos + ")");
        }
    }
}
