package org.example.stockwatch247.security;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where the server may send an outgoing request: the rules for user-supplied URLs (webhooks, Discord, push
 * endpoints) and the address ranges no request may reach, checked again on every connection so a host whose
 * DNS later points inward is refused too. Private targets are allowed only in dev and tests, where receivers and
 * fake providers run on the loopback interface.
 */
public final class OutboundTargetPolicy {
    public static final int MAX_URL_LENGTH = 2048;
    private static final Pattern NUMERIC_LABEL = Pattern.compile("0x[0-9a-f]*|[0-9]+");

    public enum Problem {
        INVALID_URL, NOT_HTTPS, CREDENTIALS, FRAGMENT, TOO_LONG, IP_LITERAL, PORT, PRIVATE_ADDRESS, OWN_HOST, UNKNOWN_HOST
    }

    public static final class TargetRejected extends RuntimeException {
        private final Problem problem;

        public TargetRejected(Problem problem) {
            super("Outgoing target refused: " + problem);
            this.problem = problem;
        }

        public Problem problem() { return problem; }
    }

    private final boolean allowPrivateTargets;
    private final boolean allowHttp;
    private final boolean allowAnyPort;
    private final String ownHost;

    public OutboundTargetPolicy(boolean allowPrivateTargets, boolean allowHttp, boolean allowAnyPort, String publicBaseUrl) {
        this.allowPrivateTargets = allowPrivateTargets;
        this.allowHttp = allowHttp;
        this.allowAnyPort = allowAnyPort;
        String host = null;
        try {
            host = publicBaseUrl == null || publicBaseUrl.isBlank() ? null : URI.create(publicBaseUrl.trim()).getHost();
        } catch (IllegalArgumentException ignored) {
            // The production validator reports a malformed public URL.
        }
        this.ownHost = host == null ? null : normalizeHost(host);
    }

    public boolean allowsPrivateTargets() { return allowPrivateTargets; }

    /**
     * A user-supplied URL, normalized (lower-case host in punycode, no trailing dot), or {@link TargetRejected}.
     * Only the shape is checked here; the addresses are checked when the request connects.
     */
    public URI validateUrl(String raw) {
        if (raw == null) throw new TargetRejected(Problem.INVALID_URL);
        String value = raw.strip();
        if (value.isEmpty()) throw new TargetRejected(Problem.INVALID_URL);
        if (value.length() > MAX_URL_LENGTH) throw new TargetRejected(Problem.TOO_LONG);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || Character.isWhitespace(c) || Character.isISOControl(c)) throw new TargetRejected(Problem.INVALID_URL);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException invalid) {
            throw new TargetRejected(Problem.INVALID_URL);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) throw new TargetRejected(Problem.NOT_HTTPS);
        if (uri.getRawUserInfo() != null) throw new TargetRejected(Problem.CREDENTIALS);
        if (uri.getRawFragment() != null) throw new TargetRejected(Problem.FRAGMENT);
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty() || authority.contains("@")) throw new TargetRejected(Problem.INVALID_URL);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null) {
            // java.net.URI leaves an internationalized host unparsed: convert it to punycode ourselves.
            int colon = authority.lastIndexOf(':');
            String hostPart = colon > 0 ? authority.substring(0, colon) : authority;
            if (colon > 0) {
                try {
                    port = Integer.parseInt(authority.substring(colon + 1));
                } catch (NumberFormatException invalid) {
                    throw new TargetRejected(Problem.INVALID_URL);
                }
            }
            try {
                host = IDN.toASCII(hostPart, IDN.USE_STD3_ASCII_RULES);
            } catch (IllegalArgumentException invalid) {
                throw new TargetRejected(Problem.INVALID_URL);
            }
        }
        host = normalizeHost(host);
        if (host.isEmpty()) throw new TargetRejected(Problem.INVALID_URL);
        if (isIpLiteral(host) && !allowPrivateTargets) throw new TargetRejected(Problem.IP_LITERAL);
        if (ownHost != null && ownHost.equals(host)) throw new TargetRejected(Problem.OWN_HOST);
        int effectivePort = port < 0 ? (scheme.equals("https") ? 443 : 80) : port;
        if (effectivePort < 1 || effectivePort > 65535) throw new TargetRejected(Problem.PORT);
        if (!allowAnyPort && effectivePort != 443 && effectivePort != 8443) throw new TargetRejected(Problem.PORT);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String hostForUri = host.contains(":") ? "[" + host + "]" : host;
        try {
            return new URI(scheme + "://" + hostForUri + (port < 0 ? "" : ":" + port) + path + query);
        } catch (URISyntaxException invalid) {
            throw new TargetRejected(Problem.INVALID_URL);
        }
    }

    /** Every address of {@code host}, refused if any of them is blocked: a mixed answer is a rebinding attempt. */
    public InetAddress[] checkedAddresses(String host) throws UnknownHostException {
        String normalized = normalizeHost(host);
        if (ownHost != null && ownHost.equals(normalized)) throw new TargetRejected(Problem.OWN_HOST);
        InetAddress[] addresses = InetAddress.getAllByName(normalized);
        if (addresses.length == 0) throw new UnknownHostException("No address.");
        if (!allowPrivateTargets) {
            for (InetAddress address : addresses) {
                if (isBlocked(address)) throw new TargetRejected(Problem.PRIVATE_ADDRESS);
            }
        }
        return addresses;
    }

    static String normalizeHost(String host) {
        String value = host.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** Dotted, decimal, octal and hex IPv4 spellings ("2130706433", "0x7f.1") and IPv6 literals. */
    static boolean isIpLiteral(String host) {
        if (host.contains(":")) return true;
        String[] labels = host.split("\\.", -1);
        if (labels.length > 4) return false;
        for (String label : labels) {
            if (label.isEmpty() || !NUMERIC_LABEL.matcher(label).matches()) return false;
        }
        return true;
    }

    /** True for loopback, private, link-local (cloud metadata), shared, documentation, multicast and reserved addresses. */
    public static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) return blockedIpv4(bytes, 0);
        if (address instanceof Inet6Address) return blockedIpv6(bytes);
        return true;
    }

    private static boolean blockedIpv4(byte[] b, int offset) {
        int a = b[offset] & 0xff, second = b[offset + 1] & 0xff, third = b[offset + 2] & 0xff;
        return a == 0 || a == 10 || a == 127 || a >= 224
                || a == 100 && second >= 64 && second <= 127
                || a == 169 && second == 254
                || a == 172 && second >= 16 && second <= 31
                || a == 192 && second == 0 && (third == 0 || third == 2)
                || a == 192 && second == 88 && third == 99
                || a == 192 && second == 168
                || a == 198 && (second == 18 || second == 19)
                || a == 198 && second == 51 && third == 100
                || a == 203 && second == 0 && third == 113;
    }

    private static boolean blockedIpv6(byte[] b) {
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) if (b[i] != 0) { firstTenZero = false; break; }
        // ::/96 (unspecified, loopback, IPv4-compatible) and ::ffff:0:0/96 (IPv4-mapped).
        if (firstTenZero && (b[10] == 0 && b[11] == 0 || (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff)) {
            boolean v4Zero = b[12] == 0 && b[13] == 0 && b[14] == 0 && b[15] == 0;
            return b[10] == 0 || v4Zero || blockedIpv4(b, 12);
        }
        int first = b[0] & 0xff, second = b[1] & 0xff;
        if ((first & 0xfe) == 0xfc) return true;                         // fc00::/7 unique local
        if (first == 0xfe && (second & 0xc0) == 0x80) return true;       // fe80::/10 link-local
        if (first == 0xfe && (second & 0xc0) == 0xc0) return true;       // fec0::/10 site-local (deprecated)
        if (first == 0xff) return true;                                  // ff00::/8 multicast
        if (first == 0x20 && second == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) return true; // 2001:db8::/32
        if (first == 0x20 && second == 0x01 && b[2] == 0 && b[3] == 0) return true;               // 2001::/32 Teredo
        if (first == 0x01 && second == 0 && b[2] == 0 && b[3] == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0)
            return true;                                                 // 100::/64 discard
        if (first == 0x00 && second == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
            boolean wellKnown = true;                                    // 64:ff9b::/96 NAT64
            for (int i = 4; i < 12; i++) if (b[i] != 0) { wellKnown = false; break; }
            return !wellKnown || blockedIpv4(b, 12);
        }
        if (first == 0x20 && second == 0x02) return blockedIpv4(b, 2);   // 2002::/16 6to4
        return false;
    }
}
