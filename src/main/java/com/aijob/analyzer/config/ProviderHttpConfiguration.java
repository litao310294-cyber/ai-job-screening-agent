package com.aijob.analyzer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration @Profile("!test")
public class ProviderHttpConfiguration {
    @Bean
    public RestClientCustomizer providerTimeouts(@Value("${app.llm.http.connect-timeout-ms:5000}") int connect,
                                                 @Value("${app.llm.http.read-timeout-ms:60000}") int read) {
        if (connect < 1 || read < 1) throw new IllegalArgumentException("Provider timeouts must be positive");
        return builder -> {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connect); factory.setReadTimeout(read);
            builder.requestFactory(factory);
        };
    }
}
