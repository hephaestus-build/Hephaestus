package de.tum.cit.aet.hephaestus.integration.directory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.ClientCredentials;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Read-only Keycloak boundary. No partial capture escapes as a successful membership set. */
@Component
@ConditionalOnServerRole
public class KeycloakDirectoryClient {
    private static final int PAGE_SIZE = 500;
    private static final int MAX_MEMBERS = 100_000;
    private static final Duration CAPTURE_LIMIT = Duration.ofMinutes(10);
    private final RestClient http;
    private final Clock clock;

    public KeycloakDirectoryClient(@Qualifier("oidcRequestFactory") ClientHttpRequestFactory requests, Clock clock) {
        this.http = RestClient.builder().requestFactory(requests).build();
        this.clock = clock;
    }

    public record Request(
            String issuer,
            ClientCredentials credentials,
            Set<String> groupIds,
            Set<String> previousSubjects,
            SyncExecutionHandle handle) {}

    public record Capture(
            Instant startedAt,
            Instant completedAt,
            Map<String, String> groupNames,
            Map<String, Set<String>> eligibleSubjects,
            Set<String> confirmedDepartures) {
        public Capture {
            groupNames = Map.copyOf(groupNames);
            eligibleSubjects = eligibleSubjects.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
            confirmedDepartures = Set.copyOf(confirmedDepartures);
        }
    }

    // HTTP exception causes retain provider bodies, which can contain credentials or personal data.
    @SuppressWarnings("PMD.PreserveStackTrace")
    public Capture capture(Request request) {
        Instant started = clock.instant();
        try {
            check(request.handle(), started, 0);
            URI admin = adminRoot(request.issuer());
            String token = token(request.issuer(), request.credentials());
            Map<String, String> names = new HashMap<>();
            Map<String, Set<String>> eligible = new HashMap<>();
            Map<String, Boolean> enabled = new HashMap<>();
            Set<String> departed = new HashSet<>();
            for (String groupId : request.groupIds()) {
                requireId(groupId);
                Group group = required(http.get()
                        .uri(endpoint(admin, "groups", groupId))
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .body(Group.class));
                if (!groupId.equals(group.id()) || group.name() == null)
                    throw new ReadFailure("Directory group metadata was incomplete");
                names.put(groupId, group.name());
                Set<String> seen = new HashSet<>();
                for (int offset = 0; ; ) {
                    check(request.handle(), started, offset);
                    List<Member> page = required(http.get()
                            .uri(page(endpoint(admin, "groups", groupId, "members"), offset))
                            .headers(h -> h.setBearerAuth(token))
                            .retrieve()
                            .body(new ParameterizedTypeReference<List<Member>>() {}));
                    if (page.isEmpty()) break;
                    for (Member member : page) {
                        requireMember(member);
                        if (!seen.add(member.id()))
                            throw new ReadFailure("Directory pagination repeated a member; retry the capture");
                        Boolean earlier = enabled.putIfAbsent(member.id(), member.enabled());
                        if (earlier != null && !earlier.equals(member.enabled()))
                            throw new ReadFailure("Directory changed during capture; retry");
                        if (Boolean.TRUE.equals(member.enabled()))
                            eligible.computeIfAbsent(member.id(), ignored -> new HashSet<>())
                                    .add(groupId);
                        else departed.add(member.id());
                    }
                    offset += page.size();
                    if (eligible.size() + departed.size() > MAX_MEMBERS)
                        throw new ReadFailure("Directory capture exceeded its member bound");
                }
            }
            for (String subject : request.previousSubjects()) {
                if (eligible.containsKey(subject) || departed.contains(subject)) continue;
                check(request.handle(), started, departed.size());
                requireId(subject);
                Member member;
                try {
                    member = required(http.get()
                            .uri(endpoint(admin, "users", subject))
                            .headers(h -> h.setBearerAuth(token))
                            .retrieve()
                            .body(Member.class));
                } catch (HttpClientErrorException.NotFound absent) {
                    departed.add(subject);
                    continue;
                }
                requireMember(member);
                if (!subject.equals(member.id()))
                    throw new ReadFailure("Directory returned a different member identity");
                if (!Boolean.TRUE.equals(member.enabled())) {
                    departed.add(subject);
                    continue;
                }
                Set<String> groups = new HashSet<>();
                for (int offset = 0; ; ) {
                    check(request.handle(), started, offset);
                    List<Group> page = required(http.get()
                            .uri(page(endpoint(admin, "users", subject, "groups"), offset))
                            .headers(h -> h.setBearerAuth(token))
                            .retrieve()
                            .body(new ParameterizedTypeReference<List<Group>>() {}));
                    if (page.isEmpty()) break;
                    for (Group group : page) {
                        requireId(required(group).id());
                        if (!groups.add(group.id()))
                            throw new ReadFailure("Directory group pagination repeated an entry");
                    }
                    offset += page.size();
                }
                if (groups.stream().anyMatch(request.groupIds()::contains))
                    throw new ReadFailure("Directory changed during capture; retry");
                departed.add(subject);
            }
            check(request.handle(), started, eligible.size());
            return new Capture(started, clock.instant(), Map.copyOf(names), Map.copyOf(eligible), Set.copyOf(departed));
        } catch (RestClientResponseException failure) {
            throw new ReadFailure("Directory request failed with HTTP "
                    + failure.getStatusCode().value() + "; verify read permissions and client credentials");
        } catch (RestClientException failure) {
            throw new ReadFailure("Directory could not be read completely; check availability and retry");
        }
    }

    private void check(SyncExecutionHandle handle, Instant started, int processed) {
        if (handle.isCancellationRequested()) {
            handle.reportCancelled();
            throw new ReadFailure("Directory capture was cancelled");
        }
        handle.progress(
                null,
                null,
                SyncProgress.of(SyncPhase.TEAMS, "Reading approved directory groups and confirming departures"));
        if (processed > MAX_MEMBERS || !clock.instant().isBefore(started.plus(CAPTURE_LIMIT)))
            throw new ReadFailure("Directory capture exceeded its safety bound; narrow the approved groups");
    }

    private String token(String issuer, ClientCredentials credentials) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", credentials.clientId());
        form.add("client_secret", credentials.clientSecret());
        Token token = required(http.post()
                .uri(issuer.replaceAll("/+$", "") + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Token.class));
        if (token.accessToken() == null || token.accessToken().isBlank())
            throw new ReadFailure("Directory did not issue an access token");
        return token.accessToken();
    }

    static URI adminRoot(String issuer) {
        URI uri = URI.create(issuer);
        String path = Objects.requireNonNullElse(uri.getPath(), "").replaceAll("/+$", "");
        int realm = path.lastIndexOf("/realms/");
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || realm < 0
                || path.substring(realm + 8).isBlank()
                || path.substring(realm + 8).contains("/"))
            throw new ReadFailure("The approved issuer must identify one Keycloak realm over HTTPS");
        try {
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    path.substring(0, realm) + "/admin" + path.substring(realm),
                    null,
                    null);
        } catch (URISyntaxException invalid) {
            throw new ReadFailure("The approved directory issuer is invalid", invalid);
        }
    }

    private static URI endpoint(URI root, String... segments) {
        return UriComponentsBuilder.fromUri(root)
                .pathSegment(segments)
                .build()
                .encode()
                .toUri();
    }

    private static URI page(URI uri, int offset) {
        return UriComponentsBuilder.fromUri(uri)
                .queryParam("first", offset)
                .queryParam("max", PAGE_SIZE)
                .queryParam("briefRepresentation", false)
                .build(true)
                .toUri();
    }

    private static void requireMember(@Nullable Member member) {
        if (member == null) throw new ReadFailure("Directory returned an incomplete member");
        requireId(member.id());
        if (member.enabled() == null) throw new ReadFailure("Directory omitted a member's enabled status");
    }

    private static void requireId(@Nullable String id) {
        if (id == null
                || id.isBlank()
                || id.length() > 512
                || id.contains("/")
                || id.contains("\\")
                || id.equals(".")
                || id.equals("..")
                || id.chars().anyMatch(Character::isISOControl))
            throw new ReadFailure("Directory returned an invalid immutable identifier");
    }

    private static <T> T required(@Nullable T value) {
        if (value == null) throw new ReadFailure("Directory returned an incomplete response");
        return value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Member(String id, @Nullable Boolean enabled) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Group(String id, @Nullable String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Token(@JsonProperty("access_token") @Nullable String accessToken) {}

    public static class ReadFailure extends RuntimeException {
        public ReadFailure(String message) {
            super(message);
        }

        private ReadFailure(String message, URISyntaxException cause) {
            super(message, cause);
        }
    }
}
