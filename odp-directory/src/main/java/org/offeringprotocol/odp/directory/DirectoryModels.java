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

    /** A result the client could not use, and why. Its index is the position in the page as sent. */
    public record Issue(int index, String message) {}

    public record SearchPage(
            List<Service> items,
            String next,
            Facets facets,
            List<Issue> issues,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public SearchPage {
            items = items == null ? List.of() : List.copyOf(items);
            issues = issues == null ? List.of() : List.copyOf(issues);
            additional = additional == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(additional));
        }
    }

    public record Suggestions(List<String> items) {
        public Suggestions {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
