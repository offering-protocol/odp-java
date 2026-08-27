package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@JsonDeserialize(builder = ServiceDocument.Builder.class)
public final class ServiceDocument {
    private final String documentOdpVersion;
    private final String documentName;
    private final String documentDescription;
    private final String documentDocumentationUrl;
    private final String documentLanguage;
    private final List<String> documentLocalizations;
    private final List<McpEndpoint> documentMcp;
    private final List<String> documentKeywords;
    private final Branding documentBranding;
    private final List<OperationDescriptor> documentOperations;
    private final Http documentHttp;
    private final Protocols documentProtocols;
    private final List<String> documentPaymentOrigins;
    private final SearchCapabilities documentSearchCapabilities;
    private final String documentStatusUrl;
    private final String documentSupportUrl;
    private final String documentWebsiteUrl;
    private final Map<String, JsonNode> documentAdditional;

    private ServiceDocument(Builder builder) {
        this.documentOdpVersion = builder.configuredOdpVersion;
        this.documentName = builder.configuredName;
        this.documentDescription = builder.configuredDescription;
        this.documentDocumentationUrl = builder.configuredDocumentationUrl;
        this.documentLanguage = builder.configuredLanguage;
        this.documentLocalizations = Copies.list(builder.configuredLocalizations);
        this.documentMcp = Copies.list(builder.configuredMcp);
        this.documentKeywords = Copies.list(builder.configuredKeywords);
        this.documentBranding = builder.configuredBranding;
        this.documentOperations = Copies.list(builder.configuredOperations);
        this.documentHttp = builder.configuredHttp;
        this.documentProtocols = builder.configuredProtocols;
        this.documentPaymentOrigins = Copies.list(builder.configuredPaymentOrigins);
        this.documentSearchCapabilities = builder.configuredSearchCapabilities;
        this.documentStatusUrl = builder.configuredStatusUrl;
        this.documentSupportUrl = builder.configuredSupportUrl;
        this.documentWebsiteUrl = builder.configuredWebsiteUrl;
        this.documentAdditional = Copies.nodes(builder.configuredAdditional);
    }

    public static Builder builder(String name, String description, String language, Http http) {
        return new Builder()
                .odpVersion(Odp.VERSION)
                .name(name)
                .description(description)
                .language(language)
                .localizations(List.of(language))
                .http(http);
    }

    public Builder toBuilder() {
        return new Builder()
                .odpVersion(documentOdpVersion)
                .name(documentName)
                .description(documentDescription)
                .documentationUrl(documentDocumentationUrl)
                .language(documentLanguage)
                .localizations(documentLocalizations)
                .mcp(documentMcp)
                .keywords(documentKeywords)
                .branding(documentBranding)
                .operations(documentOperations)
                .http(documentHttp)
                .protocols(documentProtocols)
                .paymentOrigins(documentPaymentOrigins)
                .searchCapabilities(documentSearchCapabilities)
                .statusUrl(documentStatusUrl)
                .supportUrl(documentSupportUrl)
                .websiteUrl(documentWebsiteUrl)
                .additional(documentAdditional);
    }

    @JsonProperty("odp_version")
    public String odpVersion() {
        return documentOdpVersion;
    }

    @JsonProperty("name")
    public String name() {
        return documentName;
    }

    @JsonProperty("description")
    public String description() {
        return documentDescription;
    }

    @JsonProperty("documentation_url")
    public String documentationUrl() {
        return documentDocumentationUrl;
    }

    @JsonProperty("language")
    public String language() {
        return documentLanguage;
    }

    @JsonProperty("localizations")
    public List<String> localizations() {
        return documentLocalizations;
    }

    @JsonProperty("mcp")
    public List<McpEndpoint> mcp() {
        return documentMcp;
    }

    @JsonProperty("keywords")
    public List<String> keywords() {
        return documentKeywords;
    }

    @JsonProperty("branding")
    public Branding branding() {
        return documentBranding;
    }

    @JsonProperty("operations")
    public List<OperationDescriptor> operations() {
        return documentOperations;
    }

    @JsonProperty("http")
    public Http http() {
        return documentHttp;
    }

    @JsonProperty("protocols")
    public Protocols protocols() {
        return documentProtocols;
    }

    @JsonProperty("payment_origins")
    public List<String> paymentOrigins() {
        return documentPaymentOrigins;
    }

    @JsonProperty("search_capabilities")
    public SearchCapabilities searchCapabilities() {
        return documentSearchCapabilities;
    }

    @JsonProperty("status_url")
    public String statusUrl() {
        return documentStatusUrl;
    }

    @JsonProperty("support_url")
    public String supportUrl() {
        return documentSupportUrl;
    }

    @JsonProperty("website_url")
    public String websiteUrl() {
        return documentWebsiteUrl;
    }

    @JsonAnyGetter
    public Map<String, JsonNode> additional() {
        return documentAdditional;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof ServiceDocument other)) {
            return false;
        }
        return Objects.equals(documentOdpVersion, other.documentOdpVersion)
                && Objects.equals(documentName, other.documentName)
                && Objects.equals(documentDescription, other.documentDescription)
                && Objects.equals(documentDocumentationUrl, other.documentDocumentationUrl)
                && Objects.equals(documentLanguage, other.documentLanguage)
                && Objects.equals(documentLocalizations, other.documentLocalizations)
                && Objects.equals(documentMcp, other.documentMcp)
                && Objects.equals(documentKeywords, other.documentKeywords)
                && Objects.equals(documentBranding, other.documentBranding)
                && Objects.equals(documentOperations, other.documentOperations)
                && Objects.equals(documentHttp, other.documentHttp)
                && Objects.equals(documentProtocols, other.documentProtocols)
                && Objects.equals(documentPaymentOrigins, other.documentPaymentOrigins)
                && Objects.equals(documentSearchCapabilities, other.documentSearchCapabilities)
                && Objects.equals(documentStatusUrl, other.documentStatusUrl)
                && Objects.equals(documentSupportUrl, other.documentSupportUrl)
                && Objects.equals(documentWebsiteUrl, other.documentWebsiteUrl)
                && Objects.equals(documentAdditional, other.documentAdditional);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                documentOdpVersion,
                documentName,
                documentDescription,
                documentDocumentationUrl,
                documentLanguage,
                documentLocalizations,
                documentMcp,
                documentKeywords,
                documentBranding,
                documentOperations,
                documentHttp,
                documentProtocols,
                documentPaymentOrigins,
                documentSearchCapabilities,
                documentStatusUrl,
                documentSupportUrl,
                documentWebsiteUrl,
                documentAdditional);
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String configuredOdpVersion;
        private String configuredName;
        private String configuredDescription;
        private String configuredDocumentationUrl;
        private String configuredLanguage;
        private List<String> configuredLocalizations;
        private List<McpEndpoint> configuredMcp;
        private List<String> configuredKeywords;
        private Branding configuredBranding;
        private List<OperationDescriptor> configuredOperations;
        private Http configuredHttp;
        private Protocols configuredProtocols;
        private List<String> configuredPaymentOrigins;
        private SearchCapabilities configuredSearchCapabilities;
        private String configuredStatusUrl;
        private String configuredSupportUrl;
        private String configuredWebsiteUrl;
        private Map<String, JsonNode> configuredAdditional = Map.of();

        private Builder() {}

        @JsonProperty("odp_version")
        public Builder odpVersion(String value) {
            this.configuredOdpVersion = value;
            return this;
        }

        public Builder name(String value) {
            this.configuredName = value;
            return this;
        }

        public Builder description(String value) {
            this.configuredDescription = value;
            return this;
        }

        @JsonProperty("documentation_url")
        public Builder documentationUrl(String value) {
            this.configuredDocumentationUrl = value;
            return this;
        }

        public Builder language(String value) {
            this.configuredLanguage = value;
            return this;
        }

        public Builder localizations(List<String> values) {
            this.configuredLocalizations = copy(values);
            return this;
        }

        public Builder mcp(List<McpEndpoint> values) {
            this.configuredMcp = copy(values);
            return this;
        }

        public Builder keywords(List<String> values) {
            this.configuredKeywords = copy(values);
            return this;
        }

        public Builder branding(Branding value) {
            this.configuredBranding = value;
            return this;
        }

        public Builder operations(List<OperationDescriptor> values) {
            this.configuredOperations = copy(values);
            return this;
        }

        public Builder http(Http value) {
            this.configuredHttp = value;
            return this;
        }

        public Builder protocols(Protocols value) {
            this.configuredProtocols = value;
            return this;
        }

        @JsonProperty("payment_origins")
        public Builder paymentOrigins(List<String> values) {
            this.configuredPaymentOrigins = copy(values);
            return this;
        }

        @JsonProperty("search_capabilities")
        public Builder searchCapabilities(SearchCapabilities value) {
            this.configuredSearchCapabilities = value;
            return this;
        }

        @JsonProperty("status_url")
        public Builder statusUrl(String value) {
            this.configuredStatusUrl = value;
            return this;
        }

        @JsonProperty("support_url")
        public Builder supportUrl(String value) {
            this.configuredSupportUrl = value;
            return this;
        }

        @JsonProperty("website_url")
        public Builder websiteUrl(String value) {
            this.configuredWebsiteUrl = value;
            return this;
        }

        @JsonAnySetter
        public Builder additional(String name, JsonNode value) {
            Map<String, JsonNode> values = new java.util.LinkedHashMap<>(configuredAdditional);
            values.put(name, value);
            this.configuredAdditional = Map.copyOf(values);
            return this;
        }

        public Builder additional(Map<String, JsonNode> values) {
            this.configuredAdditional = values == null ? Map.of() : Map.copyOf(values);
            return this;
        }

        public ServiceDocument build() {
            return new ServiceDocument(this);
        }

        private static <T> List<T> copy(List<T> values) {
            return values == null ? null : List.copyOf(values);
        }
    }

    public record Http(@JsonProperty("endpoint_base") String endpointBase, OpenApi openapi) {}

    public record OpenApi(String url) {}

    public record Branding(BrandingImage icon, BrandingImage logo) {}

    public record BrandingImage(String src, String type) {}

    public record McpEndpoint(String description, String name, String type, String url) {}

    public record Protocols(
            List<EnrollmentProtocol> enrollment, List<PaymentProtocol> payments, List<TrustProtocol> trust) {
        public Protocols {
            enrollment = Copies.list(enrollment);
            payments = Copies.list(payments);
            trust = Copies.list(trust);
        }

        public Protocols(List<EnrollmentProtocol> enrollment, List<PaymentProtocol> payments) {
            this(enrollment, payments, null);
        }
    }

    public record EnrollmentProtocol(String name) {}

    public record PaymentProtocol(AuthenticationRequirement authentication, String name, List<PaymentOption> options) {
        public PaymentProtocol {
            options = Copies.list(options);
        }
    }

    public record TrustProtocol(String name) {}
}
