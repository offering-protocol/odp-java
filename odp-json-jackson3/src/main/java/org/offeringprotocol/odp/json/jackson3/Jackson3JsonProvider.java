package org.offeringprotocol.odp.json.jackson3;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.OdpJsonProvider;
import org.offeringprotocol.odp.core.OdpJsonSchema;
import org.offeringprotocol.odp.core.Page;
import org.offeringprotocol.odp.core.ServiceDocument;
import org.offeringprotocol.odp.core.ValidationIssue;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class Jackson3JsonProvider implements OdpJsonProvider {
    private static final String DECODE_ERROR = "Unable to decode JSON";

    @JsonPOJOBuilder(withPrefix = "")
    private interface BuilderMixin {}

    private static final class Node extends OdpJsonNode {
        private final JsonNode value;

        private Node(JsonNode value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        @Override
        public OdpJsonNode at(String pointer) {
            return new Node(value.at(pointer));
        }

        @Override
        public boolean asBoolean(boolean defaultValue) {
            return value.asBoolean(defaultValue);
        }

        @Override
        public int asInt() {
            return value.asInt();
        }

        @Override
        public String asString() {
            return value.asString();
        }

        @Override
        public OdpJsonNode deepCopy() {
            return new Node(value.deepCopy());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Node node && value.equals(node.value);
        }

        @Override
        public Set<String> fieldNames() {
            Set<String> names = new LinkedHashSet<>();
            value.properties().forEach(entry -> names.add(entry.getKey()));
            return Set.copyOf(names);
        }

        @Override
        public void forEachEntry(BiConsumer<String, OdpJsonNode> consumer) {
            value.properties().forEach(entry -> consumer.accept(entry.getKey(), new Node(entry.getValue())));
        }

        @Override
        public OdpJsonNode get(String name) {
            JsonNode child = value.get(name);
            return child == null ? null : new Node(child);
        }

        @Override
        public boolean has(String name) {
            return value.has(name);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public boolean isArray() {
            return value.isArray();
        }

        @Override
        public boolean isEmpty() {
            return value.isEmpty();
        }

        @Override
        public boolean isNull() {
            return value.isNull();
        }

        @Override
        public boolean isObject() {
            return value.isObject();
        }

        @Override
        public boolean isString() {
            return value.isString();
        }

        @Override
        public Iterator<OdpJsonNode> iterator() {
            List<OdpJsonNode> children = new ArrayList<>();
            value.forEach(child -> children.add(new Node(child)));
            return children.iterator();
        }

        @Override
        public OdpJsonNode path(String name) {
            return new Node(value.path(name));
        }

        @Override
        public OdpJsonNode put(String name, String text) {
            requireObject().put(name, text);
            return this;
        }

        @Override
        public OdpJsonNode putObject(String name) {
            return new Node(requireObject().putObject(name));
        }

        @Override
        public OdpJsonNode remove(String name) {
            JsonNode removed = requireObject().remove(name);
            return removed == null ? null : new Node(removed);
        }

        @Override
        public void remove(Collection<String> names) {
            requireObject().remove(names);
        }

        @Override
        public boolean removeIf(Predicate<OdpJsonNode> predicate) {
            ArrayNode array = requireArray();
            boolean removed = false;
            for (int index = array.size() - 1; index >= 0; index--) {
                if (predicate.test(new Node(array.get(index)))) {
                    array.remove(index);
                    removed = true;
                }
            }
            return removed;
        }

        @Override
        public OdpJsonNode set(String name, OdpJsonNode node) {
            requireObject().set(name, unwrap(node));
            return this;
        }

        @Override
        public int size() {
            return value.size();
        }

        @Override
        public String toString() {
            return value.toString();
        }

        private ArrayNode requireArray() {
            if (!(value instanceof ArrayNode array)) {
                throw new IllegalStateException("JSON value is not an array");
            }
            return array;
        }

        private ObjectNode requireObject() {
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("JSON value is not an object");
            }
            return object;
        }
    }

    private static final class NodeDeserializer extends ValueDeserializer<OdpJsonNode> {
        @Override
        public OdpJsonNode deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            return new Node(context.readTree(parser));
        }
    }

    private static final class NodeSerializer extends ValueSerializer<OdpJsonNode> {
        @Override
        public void serialize(OdpJsonNode value, JsonGenerator generator, SerializationContext context)
                throws JacksonException {
            context.writeTree(generator, unwrap(value));
        }
    }

    @JsonDeserialize(builder = ServiceDocument.Builder.class)
    private interface ServiceDocumentMixin {}

    private static JsonNode unwrap(OdpJsonNode node) {
        if (!(node instanceof Node jacksonNode)) {
            throw new IllegalArgumentException("ODP JSON node belongs to another provider");
        }
        return jacksonNode.value;
    }

    private final JsonMapper mapper;

    public Jackson3JsonProvider() {
        SimpleModule module = new SimpleModule()
                .addDeserializer(OdpJsonNode.class, new NodeDeserializer())
                .addSerializer(OdpJsonNode.class, new NodeSerializer());
        mapper = JsonMapper.builder()
                .addMixIn(ServiceDocument.class, ServiceDocumentMixin.class)
                .addMixIn(ServiceDocument.Builder.class, BuilderMixin.class)
                .addModule(module)
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    @Override
    public Map<String, OdpJsonSchema> compileSchemas(Map<String, String> schemas) {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(schemas));
        return schemas.keySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        name -> name, name -> validator(registry.getSchema(SchemaLocation.of(name)))));
    }

    private static OdpJsonSchema validator(Schema schema) {
        return json -> schema
                .validate(
                        json,
                        InputFormat.JSON,
                        context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
                .stream()
                .map(error -> new ValidationIssue(
                        error.getKeyword(),
                        error.getMessage(),
                        Map.of(),
                        error.getInstanceLocation().toString()))
                .toList();
    }

    @Override
    public <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(DECODE_ERROR, exception);
        }
    }

    @Override
    public <T> Page<T> decodePage(String json, Class<T> itemType) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructParametricType(Page.class, itemType));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(DECODE_ERROR, exception);
        }
    }

    @Override
    public OdpJsonNode parseTree(String json) {
        try {
            JsonNode value = mapper.readTree(json);
            return value == null ? null : new Node(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(DECODE_ERROR, exception);
        }
    }

    @Override
    public <T> T treeToValue(OdpJsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(unwrap(node), type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(DECODE_ERROR, exception);
        }
    }

    @Override
    public OdpJsonNode valueToTree(Object value) {
        try {
            return new Node(mapper.valueToTree(value));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to encode JSON", exception);
        }
    }

    @Override
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to encode JSON", exception);
        }
    }
}
