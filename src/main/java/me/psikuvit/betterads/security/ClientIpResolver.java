package me.psikuvit.betterads.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the real client IP, honoring X-Forwarded-For only when the
 * immediate connection came from a configured trusted proxy. Without this,
 * any client can send a fake X-Forwarded-For header directly to the origin
 * and bypass IP-based fraud/rate checks entirely.
 */
@Component
public class ClientIpResolver {

    private final List<TrustedRange> trustedProxies;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:}") String trustedProxiesRaw) {
        this.trustedProxies = parse(trustedProxiesRaw);
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (trustedProxies.isEmpty() || remoteAddr == null) {
            return false;
        }
        try {
            byte[] addr = InetAddress.getByName(remoteAddr).getAddress();
            for (TrustedRange range : trustedProxies) {
                if (range.contains(addr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return false;
    }

    private static List<TrustedRange> parse(String raw) {
        List<TrustedRange> ranges = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ranges;
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ranges.add(TrustedRange.parse(trimmed));
            } catch (Exception ignored) {
                // skip malformed entries rather than fail startup over a config typo
            }
        }
        return ranges;
    }

    private record TrustedRange(byte[] network, int prefixBits) {
        static TrustedRange parse(String cidr) throws UnknownHostException {
            String[] parts = cidr.split("/", 2);
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefixBits = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
            return new TrustedRange(network, prefixBits);
        }

        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits) & 0xFF;
                if ((addr[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
