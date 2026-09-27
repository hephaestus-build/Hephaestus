package de.tum.cit.aet.hephaestus.integration.scm.domain.workurl;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the address of a provider page into the piece of reviewed work it shows — and nothing more. The
 * address is never dereferenced: no request, no redirect, no DNS lookup, so what it names is decided by
 * its text alone and then proven against what Hephaestus already mirrored.
 *
 * <p>Two steps rather than one because the path grammar belongs to the provider, and the provider is the
 * workspace's connection, never a guess from the hostname: {@link #page} checks structure and yields the
 * origin the caller compares with the connected one, and only then {@link #workAddress} reads the path in
 * that provider's grammar.
 *
 * <p>The grammar is deliberately tight. A page that merely begins like a pull request's address is not
 * that pull request: only the known tabs of one are accepted, and anything else is unsupported rather than
 * stripped down to something that resolves.
 */
public final class ReviewedWorkUrls {

    /** A structurally sound HTTPS address: its normalized origin and its path segments, each decoded once. */
    public record Page(String origin, List<String> segments) {
        public Page {
            segments = List.copyOf(segments);
        }
    }

    /** The work a supported page shows, named as inside its provider: repository path, kind and number. */
    public record WorkAddress(String repositoryName, ArtifactKind kind, int number) {}

    private static final String HTTPS = "https";
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int MAX_PORT = 65_535;

    /** Positive, no leading zero, and short enough that it cannot overflow an {@code int} unnoticed. */
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,9}");

    /** Abbreviated or full SHA-1, or a SHA-256 object name. */
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{7,64}");

    private static final Set<String> GITHUB_PULL_REQUEST_TABS = Set.of("files", "changes", "commits", "checks");
    private static final Set<String> GITLAB_MERGE_REQUEST_TABS = Set.of("diffs", "commits", "pipelines", "reports");

    /** GitLab separates a project's path from its own routes with this segment; no project may be named it. */
    private static final String GITLAB_ROUTE_MARKER = "-";

    /** A reserved top-level GitLab path: what follows is a group, never a project. */
    private static final String GITLAB_GROUP_ROUTE = "groups";

    private static final String GITLAB_NOTE_PREFIX = "gid://gitlab/Note/";

    private ReviewedWorkUrls() {}

    /** GitLab specifies the numeric note identity; GitHub GraphQL IDs remain opaque. */
    public static Optional<Long> commentNativeId(@Nullable IntegrationKind provider, @Nullable String ref) {
        if (provider != IntegrationKind.GITLAB || ref == null || !ref.startsWith(GITLAB_NOTE_PREFIX)) {
            return Optional.empty();
        }
        String suffix = ref.substring(GITLAB_NOTE_PREFIX.length());
        if (!suffix.matches("[1-9][0-9]{0,18}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(suffix));
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
    }

    /**
     * Empty when {@code url} is not an absolute HTTPS address with a clean path. Query and fragment are cut
     * before parsing: they never take part in identity, and a browser leaves characters in them that a
     * strict URI parser would otherwise reject.
     */
    public static Optional<Page> page(String url) {
        if (url.chars().anyMatch(c -> isControl(c) || c == '\\')) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url.substring(0, endOfPath(url)));
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getRawUserInfo() != null) {
            return Optional.empty();
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith("/")) {
            return Optional.empty();
        }
        Optional<String> origin = origin(uri);
        Optional<List<String>> segments = segments(rawPath);
        if (origin.isEmpty() || segments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Page(origin.get(), segments.get()));
    }

    /**
     * The origin of a configured provider server, normalized the way {@link #page} normalizes a page's.
     * Empty when the configured value is not a bare HTTPS origin, in which case no page can match it.
     */
    public static Optional<String> configuredOrigin(String serverUrl) {
        URI uri;
        try {
            uri = new URI(serverUrl);
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
        if (!uri.isAbsolute()
                || uri.isOpaque()
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return Optional.empty();
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty() && !rawPath.equals("/")) {
            return Optional.empty();
        }
        return origin(uri);
    }

    /** Empty when the page is not a pull request, merge request or issue in {@code provider}'s grammar. */
    public static Optional<WorkAddress> workAddress(IntegrationKind provider, Page page) {
        return switch (provider) {
            case GITHUB -> gitHub(page.segments());
            case GITLAB -> gitLab(page.segments());
            default -> Optional.empty();
        };
    }

    /**
     * {@code /owner/repo/pull/N[/tab]} or {@code /owner/repo/issues/N}. A repository is exactly two
     * segments on GitHub.
     */
    private static Optional<WorkAddress> gitHub(List<String> segments) {
        if (segments.size() < 4) {
            return Optional.empty();
        }
        Optional<Integer> number = number(segments.get(3));
        if (number.isEmpty()) {
            return Optional.empty();
        }
        String repositoryName = segments.get(0) + "/" + segments.get(1);
        List<String> tail = segments.subList(4, segments.size());
        return switch (segments.get(2)) {
            case "pull" ->
                isGitHubPullRequestTail(tail)
                        ? Optional.of(new WorkAddress(repositoryName, ScmSignals.PULL_REQUEST, number.get()))
                        : Optional.empty();
            case "issues" ->
                tail.isEmpty()
                        ? Optional.of(new WorkAddress(repositoryName, ScmSignals.ISSUE, number.get()))
                        : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static boolean isGitHubPullRequestTail(List<String> tail) {
        return tail.isEmpty()
                || (tail.size() == 1 && GITHUB_PULL_REQUEST_TABS.contains(tail.get(0)))
                || (tail.size() == 2
                        && tail.get(0).equals("commits")
                        && COMMIT_SHA.matcher(tail.get(1)).matches());
    }

    /**
     * {@code /namespace/[subgroups/]project/-/merge_requests|issues|work_items/N}. Split on the route
     * marker, not on fixed positions, so nested groups work. A project work item names an issue only if
     * one is mirrored under that number, which the caller's issue lookup decides; a group-level work item
     * or epic has no project and is unsupported rather than guessed at.
     */
    private static Optional<WorkAddress> gitLab(List<String> segments) {
        int marker = segments.indexOf(GITLAB_ROUTE_MARKER);
        if (marker < 2 || segments.get(0).equals(GITLAB_GROUP_ROUTE) || segments.size() < marker + 3) {
            return Optional.empty();
        }
        Optional<Integer> number = number(segments.get(marker + 2));
        if (number.isEmpty()) {
            return Optional.empty();
        }
        String repositoryName = String.join("/", segments.subList(0, marker));
        List<String> tail = segments.subList(marker + 3, segments.size());
        return switch (segments.get(marker + 1)) {
            case "merge_requests" ->
                tail.isEmpty() || (tail.size() == 1 && GITLAB_MERGE_REQUEST_TABS.contains(tail.get(0)))
                        ? Optional.of(new WorkAddress(repositoryName, ScmSignals.PULL_REQUEST, number.get()))
                        : Optional.empty();
            case "issues", "work_items" ->
                tail.isEmpty()
                        ? Optional.of(new WorkAddress(repositoryName, ScmSignals.ISSUE, number.get()))
                        : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static Optional<Integer> number(String segment) {
        if (!NUMBER.matcher(segment).matches()) {
            return Optional.empty();
        }
        long value = Long.parseLong(segment);
        return value > Integer.MAX_VALUE ? Optional.empty() : Optional.of((int) value);
    }

    /**
     * Lowercase scheme and host, the default HTTPS port dropped and any other kept. Hosts are compared
     * whole, never by suffix. A registry-based authority (no parseable host) has no origin.
     */
    private static Optional<String> origin(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals(HTTPS) || host == null || host.isEmpty()) {
            return Optional.empty();
        }
        int port = uri.getPort();
        if (port == 0 || port > MAX_PORT) {
            return Optional.empty();
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return Optional.of(
                port == -1 || port == DEFAULT_HTTPS_PORT
                        ? HTTPS + "://" + normalizedHost
                        : HTTPS + "://" + normalizedHost + ":" + port);
    }

    /** One trailing slash is tolerated; an empty segment anywhere else is not a path any provider serves. */
    private static Optional<List<String>> segments(String rawPath) {
        String path = rawPath.substring(1);
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return Optional.empty();
        }
        List<String> decoded = new ArrayList<>();
        for (String raw : path.split("/", -1)) {
            Optional<String> segment = decodeSegment(raw);
            if (segment.isEmpty()) {
                return Optional.empty();
            }
            decoded.add(segment.get());
        }
        return Optional.of(decoded);
    }

    /**
     * Percent-decodes one raw segment exactly once, as path decoding does ({@code +} stays {@code +}),
     * and strictly as UTF-8. A segment that decodes to a delimiter, a percent sign (a second layer of
     * encoding), a control character or a dot segment is refused rather than normalized: normalizing it
     * could walk into another project.
     */
    private static Optional<String> decodeSegment(String raw) {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b != '%') {
                out.write(b);
                continue;
            }
            if (i + 2 >= bytes.length) {
                return Optional.empty();
            }
            int high = Character.digit(bytes[i + 1], 16);
            int low = Character.digit(bytes[i + 2], 16);
            if (high < 0 || low < 0) {
                return Optional.empty();
            }
            out.write((high << 4) | low);
            i += 2;
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        } catch (CharacterCodingException malformed) {
            return Optional.empty();
        }
        if (decoded.equals(".")
                || decoded.equals("..")
                || decoded.chars().anyMatch(c -> isControl(c) || c == '/' || c == '\\' || c == '%')) {
            return Optional.empty();
        }
        return Optional.of(decoded);
    }

    private static int endOfPath(String url) {
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query < 0) {
            return fragment < 0 ? url.length() : fragment;
        }
        return fragment < 0 ? query : Math.min(query, fragment);
    }

    private static boolean isControl(int c) {
        return c < 0x20 || c == 0x7F;
    }
}
