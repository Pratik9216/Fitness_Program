package com.fitness.aiservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GeminiService {

    private final WebClient webClient;

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Value("${gemini.api.key}")
    private String geminiApiKey;


    public GeminiService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

//    public String getRecommendations(String details) {
//        Map<String, Object> requestBody = Map.of(
//                "contents", new Object[] {
//                        Map.of("parts", new Object[]{
//                                Map.of("text", details)
//                        })
//    }
//    );
//
//    String response = webClient.post()
//                        .uri(geminiApiUrl)
//                        .header("Content-Type", "application/json")
//                        .header("x-goog-api-key", geminiApiKey)
//                        .bodyValue(requestBody)
//                        .retrieve()
//                        .bodyToMono(String.class)
//                        .block();
//
//    return response;
//    }

    public String getRecommendations(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of("text", prompt)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "thinkingConfig", Map.of(
                                "thinkingBudget", 0
                        )
                )
        );

        return webClient.post()
                .uri(geminiApiUrl + "?key=" + geminiApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), response ->
                        response.bodyToMono(String.class)
                                .doOnNext(body -> log.error("Gemini 400 error body: {}", body))
                                .flatMap(body -> Mono.error(new RuntimeException("Gemini error: " + body)))
                )
                .bodyToMono(String.class)
                .block();
    }
}

