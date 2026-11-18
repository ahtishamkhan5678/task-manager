package llm;

import util.SimpleJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real LlmClient using java.net.http (built into the JDK, no libraries).
 * Stands in for the instructor-provided LlmClient.
 *
 * Provider is chosen with LLM_PROVIDER = "anthropic" (default), "openai" or "gemini" (Google, has a free tier).
 * Model can be overridden with LLM_MODEL.
 */
public class HttpLlmClient implements LlmClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Default 60 seconds; override with LLM_TIMEOUT_SECONDS (slow models or networks). */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(readTimeoutSeconds());

    private static long readTimeoutSeconds() {
        String value = System.getenv("LLM_TIMEOUT_SECONDS");
        try {
            long seconds = value == null ? 60 : Long.parseLong(value.trim());
            return seconds > 0 ? seconds : 60;
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    private final String apiKey;
    private final String provider;
    private final String model;
    private final HttpClient http;

    public HttpLlmClient(String apiKey, String provider, String model) {
        this.apiKey = apiKey;
        this.provider = provider == null || provider.isBlank() ? "anthropic" : provider.toLowerCase();
        this.model = model == null || model.isBlank() ? defaultModel(this.provider) : model;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** Reads LLM_PROVIDER and LLM_MODEL from the environment. */
    public static HttpLlmClient fromEnvironment(String apiKey) {
        return new HttpLlmClient(apiKey, System.getenv("LLM_PROVIDER"), System.getenv("LLM_MODEL"));
    }

    private static String defaultModel(String provider) {
        return switch (provider) {
            case "openai" -> "gpt-4o-mini";
            case "gemini" -> "gemini-3.5-flash-lite";
            default -> "claude-haiku-4-5";
        };
    }

    /** Provider statuses worth retrying: rate limit and temporary server overload. */
    private static final int MAX_ATTEMPTS = 3;

    @Override
    public String complete(String prompt) {
        LlmCallException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return completeOnce(prompt);
            } catch (RetryableException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    sleep(1000L * attempt * attempt); // 1 s, then 4 s
                }
            }
        }
        throw new LlmCallException(last.getMessage() + " (after " + MAX_ATTEMPTS + " attempts)", last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("LLM call was interrupted", e);
        }
    }

    /** A temporary provider failure (429 / 5xx) that is worth retrying. */
    private static final class RetryableException extends LlmCallException {
        RetryableException(String message) {
            super(message);
        }
    }

    private String completeOnce(String prompt) {
        try {
            HttpRequest request = switch (provider) {
                case "openai" -> openAiRequest(prompt);
                case "gemini" -> geminiRequest(prompt);
                default -> anthropicRequest(prompt);
            };
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 != 2) {
                String message = "LLM provider returned HTTP " + status + providerMessage(response.body());
                if (status == 429 || status == 500 || status == 502 || status == 503) {
                    throw new RetryableException(message);
                }
                throw new LlmCallException(message);
            }
            return extractText(SimpleJson.parseObject(response.body()));
        } catch (HttpTimeoutException e) {
            throw new LlmCallException("LLM call timed out", e);
        } catch (IOException e) {
            throw new LlmCallException("LLM call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("LLM call was interrupted", e);
        } catch (IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new LlmCallException("LLM provider sent an unexpected response body", e);
        }
    }

    /** Pulls the provider's own error text (all three providers use {"error":{"message":...}}). Never contains the key. */
    @SuppressWarnings("unchecked")
    private static String providerMessage(String body) {
        try {
            Object error = SimpleJson.parseObject(body).get("error");
            if (error instanceof Map<?, ?> map && map.get("message") instanceof String text) {
                String trimmed = text.length() > 160 ? text.substring(0, 160) + "..." : text;
                return " (" + trimmed + ")";
            }
        } catch (RuntimeException ignored) {
            // body was not JSON; the status code alone is enough
        }
        return "";
    }

    private HttpRequest anthropicRequest(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 1024);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(SimpleJson.write(body)))
                .build();
    }

    private HttpRequest openAiRequest(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(SimpleJson.write(body)))
                .build();
    }

    private HttpRequest geminiRequest(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(SimpleJson.write(body)))
                .build();
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> json) {
        if (provider.equals("gemini")) {
            List<Object> candidates = (List<Object>) json.get("candidates");
            Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) candidates.get(0)).get("content");
            List<Object> parts = (List<Object>) content.get("parts");
            return (String) ((Map<String, Object>) parts.get(0)).get("text");
        }
        if (provider.equals("openai")) {
            List<Object> choices = (List<Object>) json.get("choices");
            Map<String, Object> message = (Map<String, Object>) ((Map<String, Object>) choices.get(0)).get("message");
            return (String) message.get("content");
        }
        List<Object> content = (List<Object>) json.get("content");
        return (String) ((Map<String, Object>) content.get(0)).get("text");
    }
}
