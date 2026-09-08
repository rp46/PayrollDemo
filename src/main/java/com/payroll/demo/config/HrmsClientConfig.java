package com.payroll.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Builds the {@link RestClient} used to talk to the HRMS.
 *
 * <p>Both timeouts are set explicitly. The default JDK client waits forever on a read,
 * which is the failure mode that quietly parks an ingestion thread until something else
 * times out much further away from the cause.
 */
@Configuration
@EnableConfigurationProperties(HrmsProperties.class)
public class HrmsClientConfig {

    @Bean
    public RestClient hrmsRestClient(HrmsProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiToken());
        }
        if (properties.getTenant() != null && !properties.getTenant().isBlank()) {
            builder.defaultHeader("X-Tenant", properties.getTenant());
        }
        return builder.build();
    }
}
