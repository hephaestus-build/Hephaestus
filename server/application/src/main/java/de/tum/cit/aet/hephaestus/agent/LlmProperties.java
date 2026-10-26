package de.tum.cit.aet.hephaestus.agent;

import de.tum.cit.aet.hephaestus.core.security.PrivateHostAllowlist;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Instance-wide LLM knobs, bound from {@code hephaestus.llm.*}.
 *
 * @param displayCurrency Code of the second currency in which spend in USD is also shown. Empty shows USD only.
 * @param egress          what the SSRF guard will let an admin point a provider connection at
 * @param fx              where the daily reference rates come from
 */
@ConfigurationProperties(prefix = "hephaestus.llm")
@Validated
public record LlmProperties(
        @Pattern(
                regexp = "|" + SUPPORTED_DISPLAY_CURRENCIES,
                flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "must be empty or one of: " + SUPPORTED_DISPLAY_CURRENCIES)
        @DefaultValue("")
        String displayCurrency,

        @Valid @DefaultValue Egress egress,
        @Valid @DefaultValue Fx fx) {
    /**
     * Supported display currencies as a regex alternation. Widening it means widening {@code fx_rate}
     * first: that table holds a single {@code usd_per_eur} scalar.
     */
    public static final String SUPPORTED_DISPLAY_CURRENCIES = "EUR";

    public static final String ECB_DAILY_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";

    /**
     * @param allowLoopback          Accepts provider base URLs on {@code http://localhost}. Use it only in local
     *                               development, because it lets a workspace admin send provider requests to services
     *                               on the host. The private, link-local and CGNAT checks still apply.
     * @param privateHosts           Model endpoints on private networks that provider base URLs may use, as host names,
     *                               IP addresses or CIDR ranges. A host name entry lets that name resolve to a private
     *                               address. A range admits every host name that resolves into it. Only private
     *                               addresses qualify: loopback, link-local and cloud metadata addresses stay
     *                               blocked. Empty allows public hosts only.
     * @param privateHostsAllowHttp Accepts {@code http://} base URLs whose host is in {@code private-hosts}. Every other
     *                               host still needs HTTPS.
     */
    public record Egress(
            @DefaultValue("false") boolean allowLoopback,
            @DefaultValue List<String> privateHosts,
            @DefaultValue("false") boolean privateHostsAllowHttp) {

        /** @throws IllegalArgumentException for an entry that is no host name, IP address or CIDR range */
        public PrivateHostAllowlist privateHostAllowlist() {
            return PrivateHostAllowlist.parse(privateHosts);
        }
    }

    /** @param dailyUrl overridable so an air-gapped instance can point at a local ECB mirror */
    public record Fx(@NotBlank @DefaultValue(ECB_DAILY_URL) String dailyUrl) {}
}
