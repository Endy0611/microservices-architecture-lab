package com.endy.servicea.controller;

import com.endy.servicea.client.ServiceBClient;
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
@RequestMapping("/api/a")
public class ServiceAController {

    private final ServiceBClient serviceBClient;

    public ServiceAController(ServiceBClient serviceBClient) {
        this.serviceBClient = serviceBClient;
    }

    /** Simple check: who does Service A think is calling? (reads the JWT directly) */
    @GetMapping("/whoami")
    public Map<String, Object> whoami(@AuthenticationPrincipal Jwt jwt) {
        return extractUser(jwt);
    }

    /**
     * Demonstrates service-to-service calls + user propagation end to end:
     * Gateway -> Service A -> (Feign, same token relayed) -> Service B.
     */
    @GetMapping("/hello")
    public Map<String, Object> hello(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "service-a");
        body.put("message", "Hello from Service A");
        body.put("calledAt", Instant.now().toString());
        body.put("currentUser", extractUser(jwt));
        body.put("serviceBResponse", serviceBClient.helloFromB());
        return body;
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
