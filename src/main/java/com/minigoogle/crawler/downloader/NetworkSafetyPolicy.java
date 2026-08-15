package com.minigoogle.crawler.downloader;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF protection for the crawler.
 *
 * <p>Rejects URLs whose scheme is not http/https and whose destination host
 * resolves to any non-globally-routable address: loopback, RFC1918 private,
 * link-local (which covers the cloud metadata endpoints 169.254.169.254 and
 * their IPv6 equivalents), CGNAT, benchmark/test-net, multicast and reserved
 * ranges, for both IPv4 and IPv6 (including IPv4-mapped IPv6 forms).</p>
 *
 * <p>The destination host is resolved at check time and every hop of a redirect
 * chain is re-validated, so a hostname that was public at seed time but now
 * points at an internal address (or that resolves to mixed public/internal
 * addresses) is rejected. Resolution failures fail closed: if the host cannot
 * be resolved, or resolves to no addresses at all, the destination is unsafe.</p>
 */
public class NetworkSafetyPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final HostResolver resolver;
    private final boolean allowLoopback;

    /**
     * Strict policy used in production: only globally routable destinations are
     * accepted.
     */
    public NetworkSafetyPolicy() {
        this(InetAddress::getAllByName, false);
    }

    /**
     * TEST-ONLY policy: permits loopback destinations so integration tests can
     * run an in-process {@link com.sun.net.httpserver.HttpServer}. Everything
     * else (RFC1918, link-local, metadata, multicast, reserved) is still
     * blocked. Never use in production.
     */
    public NetworkSafetyPolicy(boolean allowLoopback) {
        this(InetAddress::getAllByName, allowLoopback);
    }

    NetworkSafetyPolicy(HostResolver resolver, boolean allowLoopback) {
        this.resolver = resolver;
        this.allowLoopback = allowLoopback;
    }

    /**
     * Returns true only if the URI may be fetched by the crawler.
     *
     * @param uri the target to validate (including every redirect hop)
     */
    public boolean isSafe(URI uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return false;
        }
        if (addresses.length == 0) {
            return false;
        }
        for (InetAddress address : addresses) {
            if (!isAddressAllowed(address)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAddressAllowed(InetAddress address) {
        if (allowLoopback && address.isLoopbackAddress()) {
            return true;
        }
        return isGloballyRoutable(address);
    }

    private static boolean isGloballyRoutable(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return false;
        }
        if (address.isLinkLocalAddress()) {
            return false;
        }
        if (address.isSiteLocalAddress()) {
            return false;
        }
        if (address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            if (isIpv4Mapped(b)) {
                return isGloballyRoutable(ipv4FromMapped(b));
            }
            // Unique local addresses fc00::/7 (not flagged by isSiteLocalAddress).
            if ((b[0] & 0xFE) == 0xFC) {
                return false;
            }
            // Documentation range 2001:db8::/32.
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0D && b[3] == 0xB8) {
                return false;
            }
            return true;
        }
        if (address instanceof Inet4Address) {
            byte[] b = address.getAddress();
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            int b2 = b[2] & 0xFF;
            int b3 = b[3] & 0xFF;
            // 100.64.0.0/10 CGNAT.
            if (b0 == 100 && (b1 & 0xC0) == 0x40) {
                return false;
            }
            // 198.18.0.0/15 benchmark.
            if (b0 == 198 && (b1 & 0xFE) == 18) {
                return false;
            }
            // 192.0.0.0/24 (incl. 192.0.0.9) and 192.0.2.0/24 TEST-NET-1.
            if (b0 == 192 && b1 == 0 && b2 >= 0 && b2 <= 2) {
                return false;
            }
            // 198.51.100.0/24 TEST-NET-2.
            if (b0 == 198 && b1 == 51 && b2 == 100) {
                return false;
            }
            // 203.0.113.0/24 TEST-NET-3.
            if (b0 == 203 && b1 == 0 && b2 == 113) {
                return false;
            }
            // 240.0.0.0/4 reserved, 0.0.0.0/8 unspecified.
            return b0 != 0 && b0 < 240;
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        if (b.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
    }

    private static InetAddress ipv4FromMapped(byte[] b) {
        try {
            return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException e) {
            throw new IllegalStateException("IPv4-mapped address construction cannot fail", e);
        }
    }

    /**
     * Resolves a hostname to its addresses. Package-private so tests can supply
     * a deterministic resolver instead of depending on the real DNS.
     */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
