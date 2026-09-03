package org.commonhaus.automation.hk.forwardemail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class AliasKeyTest {

    @Test
    public void testIsValidFormat() {
        assertThat(AliasKey.isValidFormat("name@domain")).isTrue();

        assertThat(AliasKey.isValidFormat(null)).isFalse();
        assertThat(AliasKey.isValidFormat("")).isFalse();
        assertThat(AliasKey.isValidFormat("noat")).isFalse();
        assertThat(AliasKey.isValidFormat("a@b@c")).isFalse();
        assertThat(AliasKey.isValidFormat("@domain")).isFalse();
        assertThat(AliasKey.isValidFormat("name@")).isFalse();
    }
}
