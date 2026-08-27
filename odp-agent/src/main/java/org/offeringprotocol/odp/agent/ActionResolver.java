package org.offeringprotocol.odp.agent;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.offeringprotocol.odp.core.OdpUris;
import org.offeringprotocol.odp.core.Offering;
import tools.jackson.databind.JsonNode;

final class ActionResolver {
    private static final int EXPECTED_OPERATION_COUNT = 1;
    private static final Set<String> HTTP_METHODS =
            Set.of("delete", "get", "head", "options", "patch", "post", "put", "trace");
    private static final Pattern OPENAPI_VERSION = Pattern.compile("3\\.1\\.\\d+(?:[-+].*)?");

    private final SupportingJsonClient supportingClient;
    private final AttributeSchemaResolver schemaResolver;

    ActionResolver(SupportingJsonClient supportingClient, AttributeSchemaResolver schemaResolver) {
        this.supportingClient = supportingClient;
        this.schemaResolver = schemaResolver;
    }

    NormalizedActions normalize(List<Offering.Action> actions, String serviceOrigin, String serviceOpenApiUrl) {
        if (actions == null || actions.isEmpty()) {
            return new NormalizedActions(List.of(), List.of());
        }
        Map<String, Integer> counts = new HashMap<>();
        actions.forEach(action -> counts.merge(action.id(), 1, Integer::sum));
        Set<String> reportedDuplicates = new HashSet<>();
        List<DiscoveredAction> normalized = new ArrayList<>();
        List<OfferingIssue> issues = new ArrayList<>();
        for (Offering.Action action : actions) {
            if (counts.get(action.id()) > EXPECTED_OPERATION_COUNT) {
                if (reportedDuplicates.add(action.id())) {
                    issues.add(issue(action.id(), "Duplicate Action identifier " + action.id()));
                }
                continue;
            }
            try {
                normalized.add(normalize(action, serviceOrigin, serviceOpenApiUrl));
            } catch (IllegalArgumentException | IllegalStateException exception) {
                issues.add(issue(action.id(), exception.getMessage()));
            }
        }
        return new NormalizedActions(normalized, issues);
    }

    ResolvedAction resolve(DiscoveredAction action, String serviceOrigin) {
        if (action.http() != null) {
            Offering.ActionRequest request = action.http().request();
            if (request == null || request.schema() == null) {
                return new ResolvedAction(action, null, null, null);
            }
            URI reference = OdpUris.resolveResourceReference(request.schema().url(), serviceOrigin);
            JsonNode schema = schemaResolver.resolve(reference).document();
            return new ResolvedAction(action, schema, null, null);
        }
        if (action.openapi() == null) {
            throw new IllegalStateException("ODP Action has no usable target");
        }
        JsonNode document = supportingClient.get(
                URI.create(action.openapi().url()),
                "application/vnd.oai.openapi+json;version=3.1, application/json;q=0.9",
                Set.of("application/vnd.oai.openapi+json", "application/json"),
                1_048_576,
                32);
        String version = document.path("openapi").asString();
        if (!OPENAPI_VERSION.matcher(version).matches()) {
            throw new IllegalStateException("ODP Action requires an OpenAPI 3.1 document");
        }
        List<JsonNode> operations = findOperations(document, action.openapi().operationId());
        if (operations.size() != EXPECTED_OPERATION_COUNT) {
            throw new IllegalStateException(
                    "ODP Action operation_id " + action.openapi().operationId() + " must resolve exactly once");
        }
        return new ResolvedAction(action, null, document, operations.get(0));
    }

    private static DiscoveredAction normalize(Offering.Action action, String serviceOrigin, String serviceOpenApiUrl) {
        if (action.http() != null) {
            URI target = OdpUris.resolveResourceReference(action.http().href(), serviceOrigin);
            return new DiscoveredAction(
                    action.authentication(),
                    action.id(),
                    action.rel(),
                    action.description(),
                    new DiscoveredAction.HttpTarget(
                            target.toString(),
                            action.http().method(),
                            action.http().request(),
                            action.http().responseContentTypes()),
                    null);
        }
        if (action.openapi() == null) {
            throw new IllegalStateException("ODP Action has no usable target");
        }
        String reference = action.openapi().url() == null
                ? serviceOpenApiUrl
                : action.openapi().url();
        if (reference == null) {
            throw new IllegalStateException("OpenAPI Action has no OpenAPI document URL");
        }
        URI target = OdpUris.resolveResourceReference(reference, serviceOrigin);
        if (!"https".equalsIgnoreCase(target.getScheme())) {
            throw new IllegalArgumentException("ODP supporting document URL must use HTTPS");
        }
        return new DiscoveredAction(
                action.authentication(),
                action.id(),
                action.rel(),
                action.description(),
                null,
                new DiscoveredAction.OpenApiTarget(
                        target.toString(), action.openapi().operationId()));
    }

    private static List<JsonNode> findOperations(JsonNode document, String operationId) {
        JsonNode paths = document.get("paths");
        if (paths == null || !paths.isObject()) {
            throw new IllegalStateException("ODP OpenAPI document must contain paths");
        }
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode path : paths) {
            if (!path.isObject()) {
                continue;
            }
            path.forEachEntry((method, operation) -> {
                if (HTTP_METHODS.contains(method.toLowerCase(Locale.ROOT))
                        && operation.isObject()
                        && operationId.equals(operation.path("operationId").asString())) {
                    matches.add(operation);
                }
            });
        }
        return matches;
    }

    private static OfferingIssue issue(String actionId, String message) {
        return new OfferingIssue(OfferingIssue.Scope.ACTION, message, actionId);
    }

    record NormalizedActions(List<DiscoveredAction> actions, List<OfferingIssue> issues) {
        NormalizedActions {
            actions = List.copyOf(actions);
            issues = List.copyOf(issues);
        }
    }
}
