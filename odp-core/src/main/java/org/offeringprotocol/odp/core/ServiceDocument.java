package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record ServiceDocument(
        @JsonProperty("odp_version") String odpVersion,
        String name,
        String description,
        @JsonProperty("documentation_url") String documentationUrl,
        String language,
        List<String> localizations,
        List<McpEndpoint> mcp,
        List<String> keywords,
        Branding branding,
        List<OperationDescriptor> operations,
        Http http,
        Protocols protocols,
        @JsonProperty("payment_origins") List<String> paymentOrigins,
        @JsonProperty("search_capabilities") SearchCapabilities searchCapabilities,
        @JsonProperty("status_url") String statusUrl,
        @JsonProperty("support_url") String supportUrl,
        @JsonProperty("website_url") String websiteUrl,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additional) {

    public ServiceDocument {
        localizations = Copies.list(localizations);
        mcp = Copies.list(mcp);
        keywords = Copies.list(keywords);
        operations = Copies.list(operations);
        paymentOrigins = Copies.list(paymentOrigins);
        additional = Copies.nodes(additional);
    }

    public record Http(@JsonProperty("endpoint_base") String endpointBase, OpenApi openapi) {}

    public record OpenApi(String url) {}

    public record Branding(BrandingImage icon, BrandingImage logo) {}

    public record BrandingImage(String src, String type) {}

    public record McpEndpoint(String description, String name, String type, String url) {}

    public record Protocols(List<EnrollmentProtocol> enrollment, List<PaymentProtocol> payments) {
        public Protocols {
            enrollment = Copies.list(enrollment);
            payments = Copies.list(payments);
        }
    }

    public record EnrollmentProtocol(String name) {}

    public record PaymentProtocol(AuthenticationRequirement authentication, String name, List<PaymentOption> options) {
        public PaymentProtocol {
            options = Copies.list(options);
        }
    }
}
