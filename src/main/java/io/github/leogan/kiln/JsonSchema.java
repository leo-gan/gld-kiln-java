package io.github.leogan.kiln;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema subset for dynamic values: booleans, integer formats int8–int64 and uint8–uint64,
 * number float/double, string, string format binary, array, object, {@code $ref} of
 * {@code #/$defs/Name}, required versus optional properties, and {@code additionalProperties}
 * as {@code dict<str,V>}. Tensors are not part of this subset.
 */
public final class JsonSchema {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonSchema() {}

    public static Schema parse(String json) {
        try {
            return parse(JSON.readTree(json));
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    public static Schema parse(Path path) throws IOException {
        return parse(JSON.readTree(path.toFile()));
    }

    public static Schema parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("schema root must be an object");
        }
        Map<String, JsonNode> defs = new HashMap<>();
        JsonNode defsNode = root.get("$defs");
        if (defsNode != null && defsNode.isObject()) {
            defsNode.fieldNames().forEachRemaining(name -> defs.put(name, defsNode.get(name)));
        }
        String title = root.path("title").asText("Root");
        Map<String, Schema> cache = new HashMap<>();
        return parseNode(root, title, defs, cache);
    }

    private static Schema parseNode(JsonNode node, String name, Map<String, JsonNode> defs, Map<String, Schema> cache) {
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            String prefix = "#/$defs/";
            if (!ref.startsWith(prefix) || ref.length() == prefix.length()) {
                throw new IllegalArgumentException("unsupported $ref " + ref);
            }
            String defName = ref.substring(prefix.length());
            Schema cached = cache.get(defName);
            if (cached != null) {
                return cached;
            }
            JsonNode target = defs.get(defName);
            if (target == null) {
                throw new IllegalArgumentException("missing $ref " + ref);
            }
            return parseNode(target, defName, defs, cache);
        }
        if (node.has("properties") || node.has("additionalProperties") || "object".equals(node.path("type").asText())) {
            return parseObject(node, name, defs, cache);
        }
        String type = node.path("type").asText("");
        String format = node.path("format").asText("");
        return switch (type) {
            case "boolean" -> Schema.BOOL;
            case "integer" -> integer(format);
            case "number" -> number(format);
            case "string" -> "binary".equals(format) ? Schema.BYTES : Schema.STR;
            case "array" -> {
                JsonNode items = node.get("items");
                if (items == null) {
                    throw new IllegalArgumentException("array items required");
                }
                yield Schema.list(parseNode(items, name + "Item", defs, cache));
            }
            default -> throw new IllegalArgumentException("unsupported schema type " + type);
        };
    }

    private static Schema parseObject(JsonNode node, String name, Map<String, JsonNode> defs, Map<String, Schema> cache) {
        Schema cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        JsonNode additional = node.get("additionalProperties");
        JsonNode properties = node.get("properties");
        boolean hasProps = properties != null && properties.isObject() && properties.size() > 0;
        if (additional != null && additional.isObject()) {
            if (hasProps) {
                throw new IllegalArgumentException(name + " has both properties and additionalProperties");
            }
            return Schema.dict(name, parseNode(additional, name + "Value", defs, cache));
        }
        if (additional != null && additional.isBoolean() && additional.booleanValue() && !hasProps) {
            throw new IllegalArgumentException(name + " additionalProperties true");
        }
        Schema shell = Schema.structShell(name);
        cache.put(name, shell);
        HashSet<String> required = new HashSet<>();
        JsonNode requiredNode = node.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode item : requiredNode) {
                required.add(item.asText());
            }
        }
        List<Schema.Field> fields = new ArrayList<>();
        if (properties != null && properties.isObject()) {
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                String fieldName = names.next();
                Schema field = parseNode(properties.get(fieldName), name + "_" + fieldName, defs, cache);
                if (!required.contains(fieldName)) {
                    field = Schema.opt(field);
                }
                fields.add(Schema.field(fieldName, field));
            }
        }
        for (String req : required) {
            if (properties == null || !properties.has(req)) {
                throw new IllegalArgumentException("required property is not declared: " + req);
            }
        }
        shell.seal(fields);
        return shell;
    }

    private static Schema integer(String format) {
        return switch (format) {
            case "int8" -> Schema.I8;
            case "int16" -> Schema.I16;
            case "int32" -> Schema.I32;
            case "int64" -> Schema.I64;
            case "uint8" -> Schema.U8;
            case "uint16" -> Schema.U16;
            case "uint32" -> Schema.U32;
            case "uint64" -> Schema.U64;
            default -> throw new IllegalArgumentException("integer format " + format);
        };
    }

    private static Schema number(String format) {
        return switch (format) {
            case "", "double" -> Schema.F64;
            case "float" -> Schema.F32;
            default -> throw new IllegalArgumentException("number format " + format);
        };
    }
}
