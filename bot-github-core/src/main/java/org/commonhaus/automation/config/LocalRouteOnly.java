package org.commonhaus.automation.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import io.quarkus.logging.Log;
import io.quarkus.vertx.web.RoutingExchange;

public interface LocalRouteOnly {

    /**
     * Returns {@code true} if the request is a direct (non-proxied) connection.
     *
     * Loopback addresses (127.0.0.1 or ::1) are always allowed.
     * When {@code adminNetwork} is present, non-loopback addresses must also fall
     * within that CIDR range. When absent, any non-proxied address is accepted.
     *
     * @param rex the routing exchange
     * @param adminNetwork optional CIDR block, e.g. {@code "172.20.0.0/24"}
     */
    default boolean isDirectConnection(RoutingExchange rex, Optional<String> adminNetwork) {
        var request = rex.context().request();

        // Reject proxied requests regardless of source address
        if (request.getHeader("X-Forwarded-For") != null
                || request.getHeader("X-Real-IP") != null) {
            return false;
        }

        String host = request.remoteAddress().host();
        if ("127.0.0.1".equals(host) || "::1".equals(host)) {
            return true;
        }

        // If a trusted network is configured, the remote address must also be in range
        if (adminNetwork.isPresent()) {
            return isInCidr(host, adminNetwork.get());
        }

        return true;
    }

    /**
     * Handle an unauthorized access attempt.
     */
    default void rejectNonLocalAccess(RoutingExchange rex) {
        rex.context().response()
                .setStatusCode(403)
                .end("Access denied");
    }

    /**
     * Returns {@code true} if {@code remoteHost} falls within {@code cidr}.
     * Supports IPv4 CIDR notation (e.g. {@code "172.20.0.0/24"}).
     * IPv4-mapped IPv6 addresses (e.g. {@code "::ffff:172.20.0.1"}) are
     * normalised to plain IPv4 before the check.
     * Logs a warning and returns {@code false} on any parse error.
     */
    static boolean isInCidr(String remoteHost, String cidr) {
        try {
            String host = normalizeHost(remoteHost);

            int slash = cidr.indexOf('/');
            if (slash < 0) {
                // No prefix — treat as exact host match
                return InetAddress.getByName(host)
                        .equals(InetAddress.getByName(cidr));
            }

            String networkAddr = cidr.substring(0, slash);
            int prefixLen = Integer.parseInt(cidr.substring(slash + 1));

            long network = ipToLong(InetAddress.getByName(networkAddr));
            long hostBits = 32 - prefixLen;
            long start = (network >> hostBits) << hostBits; // zero out host bits
            long end = start | ((1L << hostBits) - 1); // set all host bits

            long remote = ipToLong(InetAddress.getByName(host));
            return remote >= start && remote <= end;
        } catch (UnknownHostException | NumberFormatException e) {
            Log.warnf("LocalRouteOnly: could not evaluate CIDR '%s' for remote '%s': %s",
                    cidr, remoteHost, e.getMessage());
            return false;
        }
    }

    /** Strip IPv4-mapped IPv6 prefix so Docker bridge addresses match IPv4 CIDRs. */
    private static String normalizeHost(String host) {
        if (host != null && host.startsWith("::ffff:")) {
            return host.substring(7);
        }
        return host;
    }

    private static long ipToLong(InetAddress ip) {
        byte[] octets = ip.getAddress();
        long result = 0;
        for (byte octet : octets) {
            result <<= 8;
            result |= (octet & 0xFF);
        }
        return result;
    }
}
