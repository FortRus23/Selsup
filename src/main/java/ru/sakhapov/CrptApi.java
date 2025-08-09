package ru.sakhapov;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private static final String API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit must be positive");
        }
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.rateLimiter = new RateLimiter(timeUnit, requestLimit);
    }

    public void createDocument(Object document, String signature) throws IOException, InterruptedException {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(signature, "signature must not be null");

        rateLimiter.acquire();

        var body = new RequestBody(document, signature);
        String jsonBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer ")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("API error: " + response.statusCode() + " - " + response.body());
        }
    }

    private static class RateLimiter {
        private final TimeUnit timeUnit;
        private final int requestLimit;
        private final Queue<Long> timestamps = new LinkedList<>();

        public RateLimiter(TimeUnit timeUnit, int requestLimit) {
            this.timeUnit = timeUnit;
            this.requestLimit = requestLimit;
        }

        public synchronized void acquire() throws InterruptedException {
            long now = Instant.now().toEpochMilli();
            long intervalMillis = timeUnit.toMillis(1);

            while (timestamps.size() >= requestLimit &&
                    now - timestamps.peek() < intervalMillis) {
                long waitTime = intervalMillis - (now - timestamps.peek());
                wait(waitTime);
                now = Instant.now().toEpochMilli();
            }
            timestamps.add(now);
            if (timestamps.size() > requestLimit) {
                timestamps.poll();
            }
            notifyAll();
        }
    }

    private static class RequestBody {
        public final Object document;
        public final String signature;

        public RequestBody(Object document, String signature) {
            this.document = document;
            this.signature = signature;
        }
    }

}
