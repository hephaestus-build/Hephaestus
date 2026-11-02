package de.tum.cit.aet.hephaestus.core.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Egress of the source-control clients.
 *
 * @param privateHosts Host names, IP addresses or CIDR ranges of a Git provider on a private network. Only private
 *                     addresses qualify: loopback, link-local and the known cloud metadata ranges stay blocked. A range
 *                     wider than /16 (IPv4) or /48 (IPv6), outside the private ranges, or covering this server's own
 *                     address stops startup. Empty allows public hosts only.
 */
@ConfigurationProperties(prefix = "hephaestus.scm.egress")
public record ScmEgressProperties(@DefaultValue List<String> privateHosts) {}
