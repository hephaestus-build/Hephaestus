package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.ConversationLoopCredentials;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.apache.hc.core5.net.InetAddressUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

public final class JobTokenAuthenticationFilter extends AuthenticationFilter {
    static final String PRACTICE_SCRIPT_PATH = "/internal/llm/practice-script/";
    /**
     * The body of the {@code 409} for an attempt that no longer runs. No provider sends it, so a runner that reads it
     * knows its attempt ended rather than that a provider refused a call; {@code pi-model-failure.ts} holds the same.
     */
    static final String ATTEMPT_ENDED = "This attempt no longer holds its lease.";

    private static final String RUNTIME_PATH = "/internal/llm/runtime/";
    private static final String TURN_PATH = "/internal/llm/turn/";
    static final String ASSIGNMENTS_PATH = "/internal/llm/conversation/assignments";
    public static final String PRACTICE_SCRIPT_PRACTICE_HEADER = "x-hephaestus-practice";

    private static final BearerTokenAuthenticationConverter CONVERTER = converter();
    private static final List<IpAddressMatcher> PRIVATE_RANGES = List.of(
            new IpAddressMatcher("10.0.0.0/8"),
            new IpAddressMatcher("172.16.0.0/12"),
            new IpAddressMatcher("192.168.0.0/16"),
            new IpAddressMatcher("127.0.0.0/8"),
            new IpAddressMatcher("169.254.0.0/16"),
            new IpAddressMatcher("::1"),
            new IpAddressMatcher("fe80::/10"),
            new IpAddressMatcher("fc00::/7"));

    JobTokenAuthenticationFilter(
            ReviewJobRepository jobs,
            MentorTurnRepository turns,
            ConversationLoopCredentials loops,
            AttemptTokenVerifier verifier) {
        super(
                new ProviderManager(new JobTokenAuthenticationProvider(jobs, turns, loops, verifier)),
                JobTokenAuthenticationFilter::convert);
        setSuccessHandler((request, response, authentication) -> {});
        setFailureHandler((request, response, failure) -> {
            if (failure instanceof JobTokenAuthenticationProvider.AttemptLeaseConflict) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.getWriter().write(ATTEMPT_ENDED);
            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
        });
    }

    private static BearerTokenAuthenticationConverter converter() {
        var converter = new BearerTokenAuthenticationConverter();
        converter.setAuthenticationDetailsSource(incoming -> {
            String path = incoming.getRequestURI();
            return new RequestIdentity(
                    segment(path, RUNTIME_PATH),
                    segment(path, TURN_PATH),
                    "POST".equals(incoming.getMethod()) && path.equals(ASSIGNMENTS_PATH));
        });
        return converter;
    }

    private static @Nullable Authentication convert(HttpServletRequest request) {
        if (!isPrivateIp(request.getRemoteAddr())) throw new BadCredentialsException("Private gateway required");
        var authentication = CONVERTER.convert(request);
        if (authentication == null) throw new BadCredentialsException("Bearer credential required");
        return authentication;
    }

    /** The path segment after {@code prefix}: the runtime or turn id the request names; {@code null} elsewhere. */
    private static @Nullable String segment(String path, String prefix) {
        if (!path.startsWith(prefix)) return null;
        int end = path.indexOf('/', prefix.length());
        return path.substring(prefix.length(), end < 0 ? path.length() : end);
    }

    /**
     * What the request asks for, which decides what a credential may reach.
     *
     * @param turnId the turn a turn endpoint names
     * @param assignments whether this is the conversation loop's pull, {@code POST …/conversation/assignments}, the
     *     only request its credential reaches
     */
    record RequestIdentity(
            @Nullable String runtimeJobId, @Nullable String turnId, boolean assignments) {}

    static boolean isPrivateIp(@Nullable String ip) {
        if (ip == null || (!InetAddressUtils.isIPv4(ip) && !InetAddressUtils.isIPv6(ip))) return false;
        return PRIVATE_RANGES.stream().anyMatch(range -> range.matches(ip));
    }
}
