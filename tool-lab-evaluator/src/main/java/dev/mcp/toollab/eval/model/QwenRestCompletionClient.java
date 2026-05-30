package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.ws.rs.core.Response;

import java.net.URI;

public final class QwenRestCompletionClient implements QwenCompletionClient {
    private final QwenOpenAiRestClient restClient;
    private final String apiKey;

    public QwenRestCompletionClient(URI endpoint, String apiKey) {
        this.restClient = QuarkusRestClientBuilder.newBuilder()
                .baseUri(restBaseUri(endpoint))
                .build(QwenOpenAiRestClient.class);
        this.apiKey = apiKey;
    }

    @Override
    public String createCompletion(ObjectNode request) {
        String authorization = apiKey == null || apiKey.isBlank() ? null : "Bearer " + apiKey;
        try (Response response = restClient.createCompletion(authorization, request)) {
            String raw = response.readEntity(String.class);
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IllegalStateException("Qwen provider request failed with HTTP "
                        + response.getStatus() + ": " + raw);
            }
            return raw;
        }
    }

    private static URI restBaseUri(URI endpoint) {
        String rawPath = endpoint.getRawPath();
        if (rawPath == null || !rawPath.endsWith("/v1/chat/completions")) {
            return endpoint;
        }
        String prefix = rawPath.substring(0, rawPath.length() - "/v1/chat/completions".length());
        String base = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        if (!prefix.isBlank()) {
            base += prefix;
        }
        return URI.create(base);
    }
}
