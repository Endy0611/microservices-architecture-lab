package com.endy.servicea.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * TOKEN RELAY PATTERN
 * --------------------
 * Service A itself received a request carrying the real user's Keycloak
 * access token (validated by SecurityConfig). When Service A then calls
 * Service B through OpenFeign, this interceptor copies that SAME
 * Authorization header onto the outgoing Feign request.
 *
 * Result: Service B sees and validates the exact same JWT, so it can read
 * the exact same "preferred_username" / "sub" / roles as the original
 * caller — Service B never has to trust Service A's word for who the user is.
 */
@Configuration
public class FeignClientConfig {

    @Bean
    public RequestInterceptor bearerTokenRelayInterceptor() {
        return requestTemplate -> {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String authorizationHeader = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                if (authorizationHeader != null) {
                    requestTemplate.header(HttpHeaders.AUTHORIZATION, authorizationHeader);
                }
            }
        };
    }
}
