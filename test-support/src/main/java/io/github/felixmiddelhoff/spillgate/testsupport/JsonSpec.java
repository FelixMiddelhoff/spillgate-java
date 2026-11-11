package io.github.felixmiddelhoff.spillgate.testsupport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Parses the {@code generator} object of a manifest vector into a {@link Spec} (needs Gson on the classpath). */
public final class JsonSpec {
    private JsonSpec() { }

    private static String text(JsonObject params, String key) {
        JsonElement e = params.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing string param " + key);
        }
        return e.getAsString();
    }

    private static long number(JsonObject params, String key) {
        JsonElement e = params.get(key);
        if (e == null) {
            return 0;
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            try {
                return p.isString() ? Long.parseLong(p.getAsString()) : p.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException | NumberFormatException ex) {
                // fall through
            }
        }
        throw new IllegalArgumentException("param " + key + " is not an integer");
    }

    /** Parse {@code {"kind": ..., "params": {...}}}. */
    public static Spec parse(JsonObject generator) {
        JsonElement kind = generator.get("kind");
        if (kind == null) {
            throw new IllegalArgumentException("generator without kind");
        }
        JsonElement p = generator.get("params");
        if (p == null || !p.isJsonObject()) {
            throw new IllegalArgumentException("generator without params");
        }
        JsonObject params = p.getAsJsonObject();
        long w = number(params, "window");
        if (w < 0 || w > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("window out of range");
        }
        int window = (int) w;
        switch (kind.getAsString()) {
            case "zeros":
                return new Spec.Zeros(text(params, "codec"), number(params, "matches"), number(params, "size"), window);
            case "data":
                return new Spec.Data(text(params, "codec"), number(params, "seed"), number(params, "size"), window);
            case "wrap":
                JsonElement inner = params.get("inner");
                if (inner == null || !inner.isJsonObject()) {
                    throw new IllegalArgumentException("wrap without inner");
                }
                return new Spec.Wrap(text(params, "codec"), number(params, "times"), parse(inner.getAsJsonObject()), window);
            default:
                throw new IllegalArgumentException("unknown generator kind " + kind.getAsString());
        }
    }
}
