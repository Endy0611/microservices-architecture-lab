package com.endy.serviceb.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/b")
public class ServiceBController {

    /**
     * Called directly (through the gateway) OR called internally by Service A
     * via OpenFeign. Either way, the same Keycloak-issued JWT is what's being
     * validated here — that's how Service B knows who the "real" user is,
     * even though the HTTP call actually came from Service A's Feign client.
     */
    @GetMapping("/hello")
    public Map<String, Object> hello(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "service-b");
        body.put("message", "Hello from Service B");
        body.put("calledAt", Instant.now().toString());
        body.put("currentUser", extractUser(jwt));
        return body;
    }

    @GetMapping("/whoami")
    public Map<String, Object> whoami(@AuthenticationPrincipal Jwt jwt) {
        return extractUser(jwt);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractUser(Jwt jwt) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("subject", jwt.getSubject());
        user.put("preferredUsername", jwt.getClaimAsString("preferred_username"));
        user.put("email", jwt.getClaimAsString("email"));

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        List<String> roles = realmAccess != null
                ? (List<String>) realmAccess.getOrDefault("roles", List.of())
                : List.of();
        user.put("realmRoles", roles);
        return user;
    }
}
