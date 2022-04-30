package com.gradle.enterprise.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.time.Instant.now;

public final class ExportApiDependencyExtractor {

    private static final HttpUrl GRADLE_ENTERPRISE_SERVER_URL = HttpUrl.parse("https://ge.solutions-team.gradle.com");

    private static final String DEPENDENCY_GROUP_PREFIX = "org.apache.logging.log4j";
    private static final Duration PROCESS_SCANS_SINCE = Duration.ofHours(24);

    private static final String EXPORT_API_USERNAME = System.getenv("EXPORT_API_USER");
    private static final String EXPORT_API_PASSWORD = System.getenv("EXPORT_API_PASSWORD");
    private static final String EXPORT_API_ACCESS_KEY = System.getenv("EXPORT_API_ACCESS_KEY");
    private static final int MAX_BUILD_SCANS_STREAMED_CONCURRENTLY = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Instant since1Day = now().minus(PROCESS_SCANS_SINCE);

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY, 30, TimeUnit.SECONDS))
                .authenticator(Authenticators.bearerTokenOrBasic(EXPORT_API_ACCESS_KEY, EXPORT_API_USERNAME, EXPORT_API_PASSWORD))
                .protocols(ImmutableList.of(Protocol.HTTP_1_1))
                .build();
        httpClient.dispatcher().setMaxRequests(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY);
        httpClient.dispatcher().setMaxRequestsPerHost(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY);

        EventSource.Factory eventSourceFactory = EventSources.createFactory(httpClient);
        BuildDependencyExtractor dependencyExtractor = new BuildDependencyExtractor(eventSourceFactory);
        eventSourceFactory.newEventSource(requestBuilds(since1Day), dependencyExtractor);

        formatDependencies(dependencyExtractor.dependencies.get());

        // Cleanly shuts down the HTTP client, which speeds up process termination
        shutdown(httpClient);
    }

    private static void formatDependencies(Map<String, String> deps) {
        System.out.println();
        System.out.println("VERSION SUMMARY: (First Build Scan found for each dependency)");
        System.out.println("--------------------------------------------------------------");
        if (deps.isEmpty()) {
            System.out.println("No dependencies found.");
            return;
        }

        deps.forEach((dep, buildId) -> {
            System.out.println(GRADLE_ENTERPRISE_SERVER_URL.resolve("/s/" + buildId) + " : " + dep);
        });
    }

    @NotNull
    private static Request requestBuilds(Instant since1Day) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/builds/since/" + since1Day.toEpochMilli()))
                .build();
    }

    @NotNull
    private static Request requestDependencyBuildEvents(String buildId) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=ConfigurationResolutionData"))
                .build();
    }

    private static class BuildDependencyExtractor extends PrintFailuresEventSourceListener {
        final CompletableFuture<Map<String, String>> dependencies = new CompletableFuture<>();
        private final Map<String, CompletableFuture<List<String>>> perBuildDependencies = new HashMap<>();
        private final EventSource.Factory eventSourceFactory;

        private BuildDependencyExtractor(EventSource.Factory eventSourceFactory) {
            this.eventSourceFactory = eventSourceFactory;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            System.out.println("Streaming builds...");
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            JsonNode json = parse(data);
            final String buildId = json.get("buildId").asText();

            Request request = requestDependencyBuildEvents(buildId);
            SingleBuildDependencyExtractor singleBuildDependencyExtractor = new SingleBuildDependencyExtractor(buildId);
            eventSourceFactory.newEventSource(request, singleBuildDependencyExtractor);
            perBuildDependencies.put(buildId, singleBuildDependencyExtractor.dependencyVersions);
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            Map<String, String> deps = new TreeMap<>();
            for (String buildId : perBuildDependencies.keySet()) {
                try {
                    perBuildDependencies.get(buildId).get().forEach(dep ->
                        deps.putIfAbsent(dep, buildId));
                } catch (Exception e) {
                    System.out.println("WARNING: unable to load dependencies for build " + buildId);
                }
            }
            dependencies.complete(deps);
        }
    }

    private static class SingleBuildDependencyExtractor extends PrintFailuresEventSourceListener {
        public final CompletableFuture<List<String>> dependencyVersions = new CompletableFuture<>();
        private final List<String> depCollector = Lists.newArrayList();
        private final String buildId;

        private SingleBuildDependencyExtractor(String buildId) {
            this.buildId = buildId;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            System.out.println("Processing build: " + buildId);
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            if ("BuildEvent".equals(type)) { // Ignore "Build" events
                JsonNode json = parse(data);
                String eventType = json.get("type").get("eventType").asText();
                if ("ConfigurationResolutionData".equals(eventType)) {
                    onConfigurationResolutionData(json.get("data"));
                }
            }
        }

        private void onConfigurationResolutionData(JsonNode eventData) {
            JsonNode components = eventData.get("components");
            JsonNode identities = eventData.get("identities");
            if (components == null || identities == null) {
                return;
            }

            // Start from components since these are actually resolved
            List<String> deps = StreamSupport.stream(Spliterators.spliteratorUnknownSize(components.iterator(), Spliterator.ORDERED), false)
                .map(component -> {
                    String identityIndex = component.get("identity").asText();
                    return identities.get(identityIndex);
                })
                .filter(identity -> identity.get("type").asText().startsWith("ModuleComponentIdentity"))
                .filter(identity -> identity.get("group").asText().startsWith(DEPENDENCY_GROUP_PREFIX))
                .map(identity -> {
                    String group = identity.get("group").asText();
                    String module = identity.get("module").asText();
                    String version = identity.get("version").asText();
                    return String.format("%s:%s:%s", group, module, version);
                })
                .distinct()
                .peek(dependency -> System.out.println(buildId + ": " + dependency))
                .collect(Collectors.toList());

            recordDependencies(deps);
        }

        private synchronized void recordDependencies(List<String> deps) {
            // Don't complete until the `onClosed` event to avoid premature closure of stream
            depCollector.addAll(deps);
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            dependencyVersions.complete(depCollector);
        }
    }

    private static class PrintFailuresEventSourceListener extends EventSourceListener {
        @Override
        public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
            if (response != null && response.code() == 204) {
                // No content: ignore
                this.onClosed(eventSource);
                return;
            }
            if (t != null) {
                System.err.println("FAILED: " + t.getMessage());
                t.printStackTrace();
            }
            if (response != null) {
                System.err.println("Bad response: " + response);
                System.err.println("Response body: " + getResponseBody(response));
            }
            eventSource.cancel();
            this.onClosed(eventSource);
        }

        private String getResponseBody(Response response) {
            try {
                return response.body().string();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static JsonNode parse(String data) {
        try {
            return MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static void shutdown(OkHttpClient httpClient) {
        httpClient.dispatcher().cancelAll();
        MoreExecutors.shutdownAndAwaitTermination(httpClient.dispatcher().executorService(), Duration.ofSeconds(10));
    }
}
