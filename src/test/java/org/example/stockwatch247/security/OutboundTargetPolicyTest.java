package org.example.stockwatch247.security;

import org.example.stockwatch247.security.OutboundTargetPolicy.Problem;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundTargetPolicyTest {
    private final OutboundTargetPolicy production = new OutboundTargetPolicy(false, false, false, "https://stockwatch.example");

    @Test
    void acceptsOrdinaryHttpsUrlsAndNormalizesTheirHost() {
        assertThat(production.validateUrl("https://Hooks.Example.com./in/abc?x=1").toString())
                .isEqualTo("https://hooks.example.com/in/abc?x=1");
        assertThat(production.validateUrl("https://hooks.example.com:8443").toString())
                .isEqualTo("https://hooks.example.com:8443/");
        assertThat(production.validateUrl("https://bücher.example/hook").getHost()).isEqualTo("xn--bcher-kva.example");
    }

    @Test
    void refusesUrlsThatCouldReachSomethingElse() {
        assertRejected("http://hooks.example.com/", Problem.NOT_HTTPS);
        assertRejected("ftp://hooks.example.com/", Problem.NOT_HTTPS);
        assertRejected("https://user:pass@hooks.example.com/", Problem.CREDENTIALS);
        assertRejected("https://discord.com@evil.example/api/webhooks/1/x", Problem.CREDENTIALS);
        assertRejected("https://hooks.example.com/#frag", Problem.FRAGMENT);
        assertRejected("https://hooks.example.com\\@evil.example/", Problem.INVALID_URL);
        assertRejected("https://hooks.example.com/ path", Problem.INVALID_URL);
        assertRejected("https://hooks.example.com:22/", Problem.PORT);
        assertRejected("https://stockwatch.example/api/anything", Problem.OWN_HOST);
        assertRejected("https://STOCKWATCH.example./x", Problem.OWN_HOST);
        assertRejected("https://hooks.example.com/" + "a".repeat(2100), Problem.TOO_LONG);
        assertRejected("", Problem.INVALID_URL);
    }

    @Test
    void refusesIpLiteralsInEverySpelling() {
        for (String host : new String[]{"127.0.0.1", "2130706433", "0x7f.1", "0177.0.0.1", "127.1", "[::1]",
                "[::ffff:169.254.169.254]", "8.8.8.8"}) {
            assertRejected("https://" + host + "/hook", Problem.IP_LITERAL);
        }
        assertThat(OutboundTargetPolicy.isIpLiteral("cafe.be")).isFalse();
        assertThat(OutboundTargetPolicy.isIpLiteral("1password.com")).isFalse();
    }

    @Test
    void blocksPrivateReservedAndEmbeddedAddresses() throws Exception {
        for (String blocked : new String[]{"0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "172.31.255.255", "192.0.0.8", "192.0.2.1", "192.88.99.1", "192.168.1.1", "198.18.0.1",
                "198.19.255.1", "198.51.100.7", "203.0.113.9", "224.0.0.1", "240.0.0.1", "255.255.255.255",
                "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "2001:db8::1", "100::1",
                "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a9fe:a9fe", "2002:c0a8:0101::1", "2001:0:4136:e378::1",
                "fec0::1"}) {
            assertThat(OutboundTargetPolicy.isBlocked(InetAddress.getByName(blocked))).as(blocked).isTrue();
        }
        for (String open : new String[]{"8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111",
                "64:ff9b::808:808", "2002:0808:0808::1"}) {
            assertThat(OutboundTargetPolicy.isBlocked(InetAddress.getByName(open))).as(open).isFalse();
        }
    }

    @Test
    void refusesAHostWhoseAnswerIncludesAPrivateAddress() {
        assertThatThrownBy(() -> production.checkedAddresses("localhost"))
                .isInstanceOfSatisfying(OutboundTargetPolicy.TargetRejected.class,
                        rejected -> assertThat(rejected.problem()).isEqualTo(Problem.PRIVATE_ADDRESS));
        assertThatThrownBy(() -> production.checkedAddresses("stockwatch.example"))
                .isInstanceOfSatisfying(OutboundTargetPolicy.TargetRejected.class,
                        rejected -> assertThat(rejected.problem()).isEqualTo(Problem.OWN_HOST));
    }

    @Test
    void developmentAllowsLocalReceiversOverHttp() throws Exception {
        var development = new OutboundTargetPolicy(true, true, true, "http://localhost:8080");
        assertThat(development.validateUrl("http://127.0.0.1:9000/hook").getPort()).isEqualTo(9000);
        assertThat(development.checkedAddresses("127.0.0.1")).isNotEmpty();
    }

    private void assertRejected(String url, Problem problem) {
        assertThatThrownBy(() -> production.validateUrl(url)).as(url)
                .isInstanceOfSatisfying(OutboundTargetPolicy.TargetRejected.class,
                        rejected -> assertThat(rejected.problem()).isEqualTo(problem));
    }
}
