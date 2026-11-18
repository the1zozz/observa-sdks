package com.integralways.observa.sdk;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Just enough JSON for the SDK, so it needs no library that could clash with the application's: writing event bodies
 * (maps, lists, arrays, strings, numbers, booleans, dates, UUIDs, enums, optionals, and the application's own records
 * and classes, by their fields) and reading Observa's answers.
 */
final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (depth > 32) {
            // A structure this deep is almost certainly a cycle; stop rather than overflow the stack.
            out.append("null");
            return;
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence || value instanceof Character || value instanceof UUID || value instanceof Enum) {
            string(out, value instanceof Enum ? ((Enum<?>) value).name() : value.toString());
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else {
                out.append(value.toString());
            }
        } else if (value instanceof BigDecimal) {
            out.append(((BigDecimal) value).toPlainString());
        } else if (value instanceof Number) {
            out.append(value instanceof BigInteger ? value.toString() : Long.toString(((Number) value).longValue()));
        } else if (value instanceof Instant) {
            string(out, value.toString());
        } else if (value instanceof Date) {
            string(out, ((Date) value).toInstant().toString());
        } else if (value instanceof TemporalAccessor) {
            string(out, value.toString());
        } else if (value instanceof Optional) {
            write(out, ((Optional<?>) value).orElse(null), depth + 1);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, e.getKey().toString());
                out.append(':');
                write(out, e.getValue(), depth + 1);
            }
            out.append('}');
        } else if (value instanceof Collection || value instanceof Object[]) {
            Iterable<?> items = value instanceof Collection ? (Collection<?>) value : java.util.Arrays.asList((Object[]) value);
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item, depth + 1);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, java.lang.reflect.Array.get(value, i), depth + 1);
            }
            out.append(']');
        } else {
            Map<String, Object> fields = fields(value);
            if (fields != null) {
                write(out, fields, depth + 1);
            } else {
                // An object we do not know how to read field by field: its text.
                string(out, value.toString());
            }
        }
    }

    // ------------------------------------------------------------------ the application's own objects

    /** How to read one property of an application class. */
    private static final class Accessor {
        final String name;
        final Method method;

        Accessor(String name, Method method) {
            this.name = name;
            this.method = method;
        }
    }

    private static final Map<Class<?>, List<Accessor>> ACCESSORS = new ConcurrentHashMap<>();
    private static final Method IS_RECORD = classMethod("isRecord");
    private static final Method RECORD_COMPONENTS = classMethod("getRecordComponents");

    /**
     * An application object as its properties, in a map: a record's components (Java 16+), or a class's public getters
     * ({@code getTotal()}, {@code isPaid()}), as Lombok and most frameworks write them. Null for the JDK's own types and
     * for classes with neither, which are sent as their text. A getter that throws (a lazy database field, say) is left
     * out. Read on the caller's thread, when the event is recorded.
     */
    static Map<String, Object> fields(Object value) {
        if (value == null) {
            return null;
        }
        List<Accessor> accessors = ACCESSORS.computeIfAbsent(value.getClass(), Json::accessors);
        if (accessors.isEmpty()) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Accessor a : accessors) {
            try {
                map.put(a.name, a.method.invoke(value));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Logger.getLogger(Observa.LOGGER).log(Level.FINE,
                        "Observa: left out " + value.getClass().getName() + "." + a.name, e);
            }
        }
        return map;
    }

    private static List<Accessor> accessors(Class<?> type) {
        if (type.isArray() || type.isPrimitive() || type.isEnum() || type.isSynthetic() || jdk(type.getName())) {
            return Collections.emptyList();
        }
        List<Accessor> found = new ArrayList<>();
        try {
            if (IS_RECORD != null && Boolean.TRUE.equals(IS_RECORD.invoke(type))) {
                for (Object component : (Object[]) RECORD_COMPONENTS.invoke(type)) {
                    String name = (String) component.getClass().getMethod("getName").invoke(component);
                    Method accessor = (Method) component.getClass().getMethod("getAccessor").invoke(component);
                    add(found, name, accessor);
                }
                return found;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Collections.emptyList();
        }
        for (Method m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0 || m.getReturnType() == void.class
                    || m.isBridge() || m.isSynthetic() || jdk(m.getDeclaringClass().getName())) {
                continue;
            }
            String n = m.getName();
            if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))) {
                add(found, decapitalize(n.substring(3)), m);
            } else if (n.startsWith("is") && n.length() > 2 && Character.isUpperCase(n.charAt(2))
                    && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                add(found, decapitalize(n.substring(2)), m);
            }
        }
        // getMethods() has no fixed order: by name, so the same object is always written the same way.
        found.sort(Comparator.comparing(a -> a.name));
        return found;
    }

    private static void add(List<Accessor> found, String name, Method method) {
        try {
            // Public methods of a class that is not public (a nested or package-private class) need this to be called.
            method.setAccessible(true);
        } catch (RuntimeException e) {
            // A module that does not open its classes: try anyway, and leave the property out if it fails.
        }
        found.add(new Accessor(name, method));
    }

    /** The JDK's classes, and the plumbing that languages and ORMs add to the application's (proxies, meta classes). */
    private static boolean jdk(String className) {
        return className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.")
                || className.startsWith("sun.") || className.startsWith("com.sun.") || className.startsWith("groovy.")
                || className.startsWith("kotlin.") || className.startsWith("scala.") || className.startsWith("org.hibernate.");
    }

    /** As JavaBeans name properties: getTotal → total, getURL → URL. */
    private static String decapitalize(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static Method classMethod(String name) {
        try {
            return Class.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null; // before Java 16: there are no records
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------------ reading

    /** Reads JSON into maps, lists, strings, numbers (Long or Double), booleans and nulls; null if it is not JSON. */
    static Object read(String text) {
        if (text == null) {
            return null;
        }
        try {
            Reader r = new Reader(text);
            Object value = r.value();
            r.space();
            return r.at == text.length() ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Reader {
        private final String s;
        private int at;

        Reader(String s) {
            this.s = s;
        }

        Object value() {
            space();
            char c = s.charAt(at);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            space();
            if (s.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                space();
                String key = string();
                space();
                if (s.charAt(at++) != ':') {
                    throw new IllegalArgumentException("expected :");
                }
                map.put(key, value());
                space();
                char c = s.charAt(at++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or }");
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            space();
            if (s.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                space();
                char c = s.charAt(at++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or ]");
                }
            }
        }

        private String string() {
            if (s.charAt(at++) != '"') {
                throw new IllegalArgumentException("expected a string");
            }
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = s.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = s.charAt(at++);
                switch (e) {
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        out.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                        at += 4;
                        break;
                    default:
                        out.append(e);
                }
            }
        }

        private Object number() {
            int start = at;
            while (at < s.length() && "+-0123456789.eE".indexOf(s.charAt(at)) >= 0) {
                at++;
            }
            String n = s.substring(start, at);
            if (n.isEmpty()) {
                throw new IllegalArgumentException("unexpected character");
            }
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
        }

        private void expect(String word) {
            if (!s.startsWith(word, at)) {
                throw new IllegalArgumentException("expected " + word);
            }
            at += word.length();
        }

        void space() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) {
                at++;
            }
        }
    }
}
