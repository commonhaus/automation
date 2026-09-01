package org.commonhaus.automation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalRouteOnlyTest {

    // isInCidr is a static method on the interface — call it directly

    @Test
    void addressInRangeIsAccepted() {
        assertThat(LocalRouteOnly.isInCidr("172.20.0.5", "172.20.0.0/24")).isTrue();
        assertThat(LocalRouteOnly.isInCidr("172.20.0.1", "172.20.0.0/24")).isTrue();
        assertThat(LocalRouteOnly.isInCidr("172.20.0.254", "172.20.0.0/24")).isTrue();
    }

    @Test
    void addressOutsideRangeIsRejected() {
        assertThat(LocalRouteOnly.isInCidr("172.20.1.1", "172.20.0.0/24")).isFalse();
        assertThat(LocalRouteOnly.isInCidr("10.0.0.1", "172.20.0.0/24")).isFalse();
        assertThat(LocalRouteOnly.isInCidr("192.168.1.1", "172.20.0.0/24")).isFalse();
    }

    @Test
    void slashSixteenRange() {
        assertThat(LocalRouteOnly.isInCidr("10.0.0.1", "10.0.0.0/16")).isTrue();
        assertThat(LocalRouteOnly.isInCidr("10.0.255.254", "10.0.0.0/16")).isTrue();
        assertThat(LocalRouteOnly.isInCidr("10.1.0.1", "10.0.0.0/16")).isFalse();
    }

    @Test
    void slashThirtyTwoExactHost() {
        assertThat(LocalRouteOnly.isInCidr("172.20.0.5", "172.20.0.5/32")).isTrue();
        assertThat(LocalRouteOnly.isInCidr("172.20.0.6", "172.20.0.5/32")).isFalse();
    }

    @Test
    void noPrefixExactMatch() {
        assertThat(LocalRouteOnly.isInCidr("172.20.0.5", "172.20.0.5")).isTrue();
        assertThat(LocalRouteOnly.isInCidr("172.20.0.6", "172.20.0.5")).isFalse();
    }

    @Test
    void ipv4MappedIpv6IsNormalized() {
        // Docker may present the remote address as an IPv4-mapped IPv6 address
        assertThat(LocalRouteOnly.isInCidr("::ffff:172.20.0.5", "172.20.0.0/24")).isTrue();
        assertThat(LocalRouteOnly.isInCidr("::ffff:10.0.0.1", "172.20.0.0/24")).isFalse();
    }

    @Test
    void bogusInputReturnsFalse() {
        assertThat(LocalRouteOnly.isInCidr("not-an-ip", "172.20.0.0/24")).isFalse();
        assertThat(LocalRouteOnly.isInCidr("172.20.0.1", "not-a-cidr/24")).isFalse();
        assertThat(LocalRouteOnly.isInCidr("172.20.0.1", "172.20.0.0/abc")).isFalse();
    }
}
