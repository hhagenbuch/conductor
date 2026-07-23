package io.github.hhagenbuch.conductor.flock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Extracts a JSON-Schema-shaped view of a Java DTO ... its fields, their JSON
/// types, and which are required ... so mcp-pact's `SchemaShape` can diff two
/// versions of the same DTO and grade the change. Handles the two shapes a
/// request/response type usually takes: a Java `record` (component list) and a
/// plain class with field declarations.
///
/// This is deliberately a lightweight source parser, not a compiler: it reads
/// the first record header or, failing that, the class's field declarations. In
/// the honest spirit of {@link io.github.hhagenbuch.conductor.lease.GitClassifier},
/// it is a good-enough shape reader, not a Java front-end ... anything it cannot
/// parse yields an empty shape, and an empty-vs-empty diff is silence, never a
/// false alert.
///
/// Type mapping to JSON Schema: String/char/enum-like → string; the numeric
/// boxes and primitives → number; boolean → boolean; List/Set/Map/array →
/// array; anything else → object. `Optional<X>` unwraps to X and marks the
/// field NOT required; every other field is required (the conservative choice,
/// so adding a non-Optional field reads as a new required field = BREAKING).
public final class JavaShape {

    private static final ObjectMapper JSON = new ObjectMapper();

    // record Name(  <components>  )   ... captures the component list, allowing
    // annotations, generics with nested commas are handled by the splitter.
    private static final Pattern RECORD_HEADER = Pattern.compile(
            "\\brecord\\s+\\w+\\s*\\(([^;{]*?)\\)\\s*(?:implements[^{]*)?\\{", Pattern.DOTALL);

    // a class field declaration: [modifiers] Type name [= ...];  (best effort)
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"           // annotations
            + "(?:private|protected|public)\\s+"
            + "(?:final\\s+|static\\s+|transient\\s+|volatile\\s+)*"
            + "([\\w.<>\\[\\]?, ]+?)\\s+"                    // type
            + "(\\w+)\\s*(?:=[^;]*)?;",                     // name [= init];
            Pattern.MULTILINE);

    /// Build a JSON-Schema object node (`{type,properties,required}`) from Java
    /// source. Returns an empty-shape node if nothing parseable is found.
    public static ObjectNode schemaOf(String javaSource) {
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var required = JSON.createArrayNode();
        if (javaSource != null && !javaSource.isBlank()) {
            var recordHeader = RECORD_HEADER.matcher(stripLineComments(javaSource));
            if (recordHeader.find()) {
                parseComponents(recordHeader.group(1), props, required);
            } else {
                parseClassFields(javaSource, props, required);
            }
        }
        schema.set("required", required);
        return schema;
    }

    private static void parseComponents(String componentList, ObjectNode props, ArrayNode required) {
        for (var component : splitTopLevel(componentList)) {
            var c = component.trim().replaceAll("@\\w+(?:\\([^)]*\\))?\\s+", "");
            if (c.isBlank()) {
                continue;
            }
            int lastSpace = c.lastIndexOf(' ');
            if (lastSpace < 0) {
                continue;
            }
            var type = c.substring(0, lastSpace).trim();
            var name = c.substring(lastSpace + 1).trim().replaceAll("[^\\w]", "");
            if (name.isEmpty()) {
                continue;
            }
            addField(name, type, props, required);
        }
    }

    private static void parseClassFields(String source, ObjectNode props, ArrayNode required) {
        var m = FIELD.matcher(source);
        while (m.find()) {
            addField(m.group(2).trim(), m.group(1).trim(), props, required);
        }
    }

    private static void addField(String name, String type, ObjectNode props, ArrayNode required) {
        boolean optional = false;
        var t = type.trim();
        var opt = Pattern.compile("^Optional\\s*<\\s*(.+)\\s*>$").matcher(t);
        if (opt.matches()) {
            optional = true;
            t = opt.group(1).trim();
        }
        props.putObject(name).put("type", jsonType(t));
        if (!optional) {
            required.add(name);
        }
    }

    private static String jsonType(String javaType) {
        var t = javaType.replaceAll("\\s+", "");
        boolean array = t.endsWith("[]");
        // strip generics (`List<String>` → `List`) and package (`java.util.List` → `List`)
        var base = t;
        int lt = base.indexOf('<');
        if (lt >= 0) {
            base = base.substring(0, lt);
        }
        if (base.endsWith("[]")) {
            base = base.substring(0, base.length() - 2);
        }
        int dot = base.lastIndexOf('.');
        var simple = dot >= 0 ? base.substring(dot + 1) : base;
        if (array) {
            return "array";
        }
        return switch (simple) {
            case "List", "Set", "Collection", "Iterable", "Queue", "Deque" -> "array";
            case "Map" -> "object";
            case "String", "char", "Character", "CharSequence", "UUID" -> "string";
            case "boolean", "Boolean" -> "boolean";
            case "int", "long", "short", "byte", "double", "float",
                 "Integer", "Long", "Short", "Byte", "Double", "Float",
                 "BigDecimal", "BigInteger", "Number" -> "number";
            default -> "object";
        };
    }

    /// Split a component list on top-level commas only, so generics like
    /// `Map<String,Integer>` are not split inside their angle brackets.
    private static java.util.List<String> splitTopLevel(String s) {
        var out = new java.util.ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '<' || ch == '(') {
                depth++;
            } else if (ch == '>' || ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out;
    }

    private static String stripLineComments(String s) {
        return s.replaceAll("(?m)//.*$", "");
    }

    private JavaShape() {
    }
}
