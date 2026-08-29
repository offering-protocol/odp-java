package org.offeringprotocol.odp.json.jackson2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
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

public final class Jackson2JsonProvider implements OdpJsonProvider {
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
            return value.asText();
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
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }

        @Override
        public void forEachEntry(BiConsumer<String, OdpJsonNode> consumer) {
            value.fields().forEachRemaining(entry -> consumer.accept(entry.getKey(), new Node(entry.getValue())));
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
            return value.isTextual();
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

    private static final class NodeDeserializer extends JsonDeserializer<OdpJsonNode> {
        @Override
        public OdpJsonNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return new Node(parser.getCodec().readTree(parser));
        }
    }

    private static final class NodeSerializer extends JsonSerializer<OdpJsonNode> {
        @Override
        public void serialize(OdpJsonNode value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeTree(unwrap(value));
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

    private final ObjectMapper mapper;

    public Jackson2JsonProvider() {
        SimpleModule module = new SimpleModule()
                .addDeserializer(OdpJsonNode.class, new NodeDeserializer())
                .addSerializer(OdpJsonNode.class, new NodeSerializer());
        mapper = new ObjectMapper()
                .addMixIn(ServiceDocument.class, ServiceDocumentMixin.class)
                .addMixIn(ServiceDocument.Builder.class, BuilderMixin.class)
                .registerModule(module)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
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
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(DECODE_ERROR, exception);
        }
    }

    @Override
    public <T> Page<T> decodePage(String json, Class<T> itemType) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructParametricType(Page.class, itemType));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(DECODE_ERROR, exception);
        }
    }

    @Override
    public OdpJsonNode parseTree(String json) {
        try {
            JsonNode value = mapper.readTree(json);
            return value == null ? null : new Node(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(DECODE_ERROR, exception);
        }
    }

    @Override
    public <T> T treeToValue(OdpJsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(unwrap(node), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(DECODE_ERROR, exception);
        }
    }

    @Override
    public OdpJsonNode valueToTree(Object value) {
        return new Node(mapper.valueToTree(value));
    }

    @Override
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode JSON", exception);
        }
    }
}
