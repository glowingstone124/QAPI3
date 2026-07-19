package org.qo.utils;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class IPUtil {
    public static String getIpAddr(ServerHttpRequest request) {
        if (request.getRemoteAddress() == null || request.getRemoteAddress().getAddress() == null) return "unknown";
        String remoteAddress = request.getRemoteAddress().getAddress().getHostAddress();
        if (!trustedProxies().contains(remoteAddress)) return remoteAddress;

        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remoteAddress;
        String candidate = forwarded.split(",", 2)[0].trim();
        if (!candidate.matches("^[0-9a-fA-F:.]{2,45}$")) {
            return remoteAddress;
        }
        return candidate;
    }

    private static Set<String> trustedProxies() {
        String configured = System.getenv("TRUSTED_PROXY_IPS");
        if (configured == null || configured.isBlank()) return Set.of();
        return Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
