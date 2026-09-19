package org.offeringprotocol.odp.directory;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.PaymentOption;
import org.offeringprotocol.odp.core.ServiceDocument;

public interface DirectoryModels {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ResourceSearchRequest(String query, ServiceFilters filters, Integer limit, List<String> types) {
        public ResourceSearchRequest {
            if (query != null && (query.isBlank() || query.length() > 512)) {
                throw new IllegalArgumentException("query must contain from 1 through 512 characters");
            }
            if (limit != null && (limit < 1 || limit > 100)) {
                throw new IllegalArgumentException("limit must be from 1 through 100");
            }
            if (types != null) {
                if (types.isEmpty()
                        || types.size() > 2
                        || types.stream().distinct().count() != types.size()
                        || types.stream().anyMatch(type -> !"service".equals(type) && !"collection".equals(type))) {
                    throw new IllegalArgumentException("types must contain distinct service or collection values");
                }
            }
            types = types == null ? List.of() : List.copyOf(types);
        }
    }

    public sealed interface Result permits ServiceResult, CollectionResult, UnknownResult {
        String type();
    }

    public record ServiceResult(
            Service service, Instant indexedAt, ServiceReference availableThrough, Map<String, OdpJsonNode> additional)
            implements Result {
        public ServiceResult {
            additional = Collections.unmodifiableMap(new LinkedHashMap<>(additional));
        }

        @Override
        public String type() {
            return "service";
        }
    }

    public record CollectionResult(
            Service service, Instant indexedAt, CollectionSummary collection, Map<String, OdpJsonNode> additional)
            implements Result {
        public CollectionResult {
            additional = Collections.unmodifiableMap(new LinkedHashMap<>(additional));
        }

        @Override
        public String type() {
            return "collection";
        }
    }

    public record UnknownResult(String type, OdpJsonNode raw) implements Result {
        public UnknownResult {
            raw = raw.deepCopy();
        }

        @Override
        public OdpJsonNode raw() {
            return raw.deepCopy();
        }
    }

    public record ServiceReference(
            String serviceId, String serviceOrigin, String name, Map<String, OdpJsonNode> additional) {
        public ServiceReference {
            additional = Collections.unmodifiableMap(new LinkedHashMap<>(additional));
        }
    }

    public record CollectionSummary(String id, String name, String description, Map<String, OdpJsonNode> additional) {
        public CollectionSummary {
            additional = Collections.unmodifiableMap(new LinkedHashMap<>(additional));
        }
    }

    public record Issue(int index, String message) {}

    public record SearchResponse(
            List<Result> items, String next, Facets facets, List<Issue> issues, Map<String, OdpJsonNode> additional) {
        public SearchResponse {
            items = List.copyOf(items);
            issues = List.copyOf(issues);
            additional = Collections.unmodifiableMap(new LinkedHashMap<>(additional));
        }
    }

    public record SearchRequest(String query, ServiceFilters filters, Integer limit) {
        public SearchRequest {
            if (limit != null && (limit < 1 || limit > 100)) {
                throw new IllegalArgumentException("limit must be from 1 through 100");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ServiceFilters(
            List<ServiceDocument.EnrollmentProtocol> enrollment,
            List<String> keywords,
            List<OperationFilter> operations,
            List<PaymentFilter> payments,
            List<ServiceDocument.TrustProtocol> trust) {
        public ServiceFilters(
                List<ServiceDocument.EnrollmentProtocol> enrollment,
                List<String> keywords,
                List<OperationFilter> operations,
                List<PaymentFilter> payments) {
            this(enrollment, keywords, operations, payments, null);
        }

        public ServiceFilters {
            if (trust != null
                    && (trust.size() != 1
                            || trust.get(0) == null
                            || !"tap".equals(trust.get(0).name()))) {
                throw new IllegalArgumentException("trust must contain exactly one tap descriptor");
            }
            enrollment = enrollment == null ? List.of() : List.copyOf(enrollment);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            operations = operations == null ? List.of() : List.copyOf(operations);
            payments = payments == null ? List.of() : List.copyOf(payments);
            trust = trust == null ? List.of() : List.copyOf(trust);
        }
    }

    public record OperationFilter(AuthenticationRequirement authentication, OdpOperation name) {}

    public record PaymentFilter(AuthenticationRequirement authentication, String name, List<PaymentOption> options) {
        public PaymentFilter {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record Service(
            @JsonProperty("service_origin") String serviceOrigin,
            String name,
            String description,
            @JsonProperty("documentation_url") String documentationUrl,
            String language,
            List<String> localizations,
            List<String> keywords,
            List<OperationDescriptor> operations,
            ServiceDocument.Protocols protocols,
            @JsonProperty("indexed_at") Instant indexedAt,
            @JsonProperty("status_url") String statusUrl,
            @JsonProperty("support_url") String supportUrl,
            @JsonProperty("website_url") String websiteUrl,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public Service {
            localizations = localizations == null ? List.of() : List.copyOf(localizations);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            operations = operations == null ? List.of() : List.copyOf(operations);
            additional = additional == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(additional));
        }

        public String serviceId() {
            OdpJsonNode value = additional.get("service_id");
            return value == null ? null : value.asString();
        }
    }

    public record Facet<T>(T value, long count) {}

    public record PaymentOptionFacetValue(String name, PaymentOption option) {}

    public record Facets(
            List<Facet<ServiceDocument.EnrollmentProtocol>> enrollment,
            List<Facet<String>> keywords,
            List<Facet<OperationDescriptor>> operations,
            List<Facet<ServiceDocument.PaymentProtocol>> payments,
            @JsonProperty("payment_options") List<Facet<PaymentOptionFacetValue>> paymentOptions,
            List<Facet<ServiceDocument.TrustProtocol>> trust) {
        public Facets(
                List<Facet<ServiceDocument.EnrollmentProtocol>> enrollment,
                List<Facet<String>> keywords,
                List<Facet<OperationDescriptor>> operations,
                List<Facet<ServiceDocument.PaymentProtocol>> payments,
                List<Facet<PaymentOptionFacetValue>> paymentOptions) {
            this(enrollment, keywords, operations, payments, paymentOptions, null);
        }

        public Facets {
            enrollment = enrollment == null ? List.of() : List.copyOf(enrollment);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            operations = operations == null ? List.of() : List.copyOf(operations);
            payments = payments == null ? List.of() : List.copyOf(payments);
            paymentOptions = paymentOptions == null ? List.of() : List.copyOf(paymentOptions);
            trust = trust == null ? List.of() : List.copyOf(trust);
        }
    }

    public record SearchPage(
            List<Service> items,
            String next,
            Facets facets,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public SearchPage {
            items = items == null ? List.of() : List.copyOf(items);
            additional = additional == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(additional));
        }
    }

    public record Suggestions(List<String> items) {
        public Suggestions {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
