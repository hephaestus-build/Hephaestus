package de.tum.cit.aet.hephaestus.core.security;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class DatabaseTransportGuard {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "postgres");

    /** TLS-establishing pgJDBC modes; everything else (disable/allow/prefer) may go plaintext. */
    private static final Set<String> TLS_SSL_MODES = Set.of("require", "verify-ca", "verify-full");

    private final Environment environment;
    private final String datasourceUrl;
    private final boolean allowInsecureRemote;

    public DatabaseTransportGuard(
            Environment environment,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${hephaestus.database.allow-insecure-remote:false}") boolean allowInsecureRemote) {
        this.environment = environment;
        this.datasourceUrl = datasourceUrl;
        this.allowInsecureRemote = allowInsecureRemote;
    }

    @PostConstruct
    void assertRemoteDatabaseUsesTls() {
        if (!environment.acceptsProfiles(Profiles.of("prod")) || allowInsecureRemote) return;
        String prefix = "jdbc:postgresql://";
        int pathStart = datasourceUrl.indexOf('/', prefix.length());
        if (!datasourceUrl.startsWith(prefix) || pathStart < 0) {
            throw new IllegalStateException("Cannot validate the production PostgreSQL URL in spring.datasource.url");
        }
        String hosts = datasourceUrl.substring(prefix.length(), pathStart);
        boolean remote = Arrays.stream(hosts.split(","))
                .map(DatabaseTransportGuard::withoutPort)
                .anyMatch(host -> !LOCAL_HOSTS.contains(host));
        if (!remote) return;
        int queryStart = datasourceUrl.indexOf('?', pathStart);
        String query = queryStart < 0 ? "" : datasourceUrl.substring(queryStart + 1);
        // pgJDBC matches sslmode VALUES case-insensitively (org.postgresql.jdbc.SslMode.of uses
        // equalsIgnoreCase), so `sslmode=DISABLE` connects plaintext — compare case-insensitively.
        // On a repeated parameter the last occurrence wins in pgJDBC; requiring EVERY occurrence to
        // be a TLS mode fails closed regardless of ordering.
        var sslModes = Arrays.stream(query.split("&"))
                .filter(parameter -> parameter.regionMatches(true, 0, "sslmode=", 0, 8))
                .map(parameter -> parameter.substring(8).toLowerCase(Locale.ROOT))
                .toList();
        boolean tls = !sslModes.isEmpty() && sslModes.stream().allMatch(TLS_SSL_MODES::contains);
        if (!tls) {
            throw new IllegalStateException("Remote PostgreSQL host '" + hosts
                    + "' must use TLS. Set sslmode=require, verify-ca, or verify-full. "
                    + "Set HEPHAESTUS_DATABASE_ALLOW_INSECURE_REMOTE=true only if you accept plaintext transport.");
        }
    }

    private static String withoutPort(String host) {
        if (host.startsWith("[")) {
            int closingBracket = host.indexOf(']');
            return closingBracket > 0 ? host.substring(1, closingBracket) : host;
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }
}
