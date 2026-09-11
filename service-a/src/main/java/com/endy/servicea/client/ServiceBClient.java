package com.endy.servicea.client;

import com.endy.servicea.config.FeignClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/**
 * "service-b" here is the Eureka application name (spring.application.name
 * of Service B) — Feign + spring-cloud-loadbalancer resolve it to a real
 * host:port via the Discovery Server, so no hardcoded URL is needed.
 */
@FeignClient(name = "service-b", configuration = FeignClientConfig.class)
public interface ServiceBClient {

    @GetMapping("/api/b/hello")
    Map<String, Object> helloFromB();

    @GetMapping("/api/b/whoami")
    Map<String, Object> whoamiFromB();
}
