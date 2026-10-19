package com.xiaoli.aiagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/** Spring AI 同步模型调用使用 HTTP/1.1，避开兼容网关重置 HTTP/2 流的问题。 */
@Configuration(proxyBeanMethods = false)
public class AiHttpClientConfig {
    @Bean
    public RestClientCustomizer aiRestClientCustomizer(
            @Value("${ai.http.connect-timeout-seconds:30}") long connectSeconds,
            @Value("${ai.http.read-timeout-seconds:180}") long readSeconds) {
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectSeconds)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(readSeconds));
        return builder -> builder.requestFactory(factory);
    }
}
