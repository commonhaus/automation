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
        assertThat(AliasKey.isValidFormat(" name@domain")).isFalse();
        assertThat(AliasKey.isValidFormat("na me@domain")).isFalse();
        assertThat(AliasKey.isValidFormat("name@dom\r\nain")).isFalse();
    }

    @Test
    public void testNormalize() {
        assertThat(AliasKey.normalize("name@domain")).isEqualTo("name@domain");
        assertThat(AliasKey.normalize("  name@domain  ")).isEqualTo("name@domain");

        assertThat(AliasKey.normalize(null)).isNull();
        assertThat(AliasKey.normalize("")).isNull();
        assertThat(AliasKey.normalize("   ")).isNull();
        assertThat(AliasKey.normalize("noat")).isNull();
        assertThat(AliasKey.normalize("na me@domain")).isNull();
    }
}
