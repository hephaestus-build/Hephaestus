package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

/**
 * Pins the audit-IP sanitizer: a malformed/unparseable {@code getRemoteAddr()} value must drop to
 * null rather than reach the Postgres {@code inet} column and fail the issued_jwt INSERT (which,
 * because issuance is transactional, would block login). Valid IPv4/IPv6 literals pass through, and
 * a hostname is never DNS-resolved.
 */
class HephaestusJwtIssuerIpSanitizeTest extends BaseUnitTest {

    @Test
    void validIpv4_passesThrough() {
        assertThat(HephaestusJwtIssuer.sanitizeIp("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void validIpv6_passesThrough() {
        assertThat(HephaestusJwtIssuer.sanitizeIp("2001:db8::1")).isEqualTo("2001:db8::1");
    }

    @Test
    void hostname_isRejectedWithoutDnsLookup() {
        // Only an IP literal is parsed, so a name never reaches DNS.
        assertThat(HephaestusJwtIssuer.sanitizeIp("evil.example.com")).isNull();
        assertThat(HephaestusJwtIssuer.sanitizeIp("cafe")).isNull();
    }

    @Test
    void garbage_isRejected() {
        assertThat(HephaestusJwtIssuer.sanitizeIp("not an ip")).isNull();
        assertThat(HephaestusJwtIssuer.sanitizeIp("999.999.999.999")).isNull();
    }

    @Test
    void nullOrBlank_isNull() {
        assertThat(HephaestusJwtIssuer.sanitizeIp(null)).isNull();
        assertThat(HephaestusJwtIssuer.sanitizeIp("  ")).isNull();
    }
}
