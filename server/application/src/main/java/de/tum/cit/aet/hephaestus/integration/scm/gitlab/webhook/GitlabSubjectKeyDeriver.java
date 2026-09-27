package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Builds NATS subjects + dedup keys for GitLab webhook payloads under the unified
 * integration framework.
 *
 * <p>Subject format: {@code gitlab.<namespace~with~tildes>.<project>.<event>}
 * <ul>
 *   <li>{@code namespace} and {@code project} from {@code project.path_with_namespace}
 *       split on {@code /} (last segment is project; everything before joined with
 *       {@code ~}, since {@code /} is the NATS hierarchy separator and groups can be
 *       nested arbitrarily deep).
 *   <li>{@code event} from payload {@code object_kind} ({@code merge_request},
 *       {@code push}, {@code issue}, {@code note}, {@code pipeline}, {@code build},
 *       {@code wiki_page}, …). Lowercased via {@link Locale#ROOT} to dodge the
 *       Turkish-i pitfall.
 * </ul>
 *
 * <p>Dedup key priority (per ADR 0008): {@code Idempotency-Key} (GitLab 17.4+) →
 * {@code X-Gitlab-Event-UUID} (16.2+) → SHA-256 of {@code body + event}, truncated to
 * 32 hex chars. The vendor prefix {@code "gitlab-"} ensures cross-vendor uniqueness
 * even if two providers happen to issue the same UUID.
 */
@Component
public class GitlabSubjectKeyDeriver implements SubjectKeyDeriver {

    static final String SUBJECT_PREFIX = "gitlab.";
    static final String UNKNOWN_TOKEN = "unknown";
    static final String PLACEHOLDER = "?";

    /**
     * Namespace token of a connection-scoped subject, {@code gitlab.?connection.<connectionId>.<event>}. Only the
     * connection endpoint publishes it, after verifying that connection's credential; a namespace taken from a payload
     * on the shared endpoint that spells it is replaced by {@link #PLACEHOLDER}.
     */
    public static final String CONNECTION_TOKEN = "?connection";

    private static final String HEADER_EVENT_UUID = "x-gitlab-event-uuid";
    private static final String HEADER_IDEMPOTENCY_KEY = "idempotency-key";

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public String deriveSubject(JsonNode payload, Map<String, String> headers) {
        String event = eventToken(payload);

        // Group-tier lifecycle events (project/subgroup/member) are ORG-scoped. They carry no routable
        // project — subgroup/member payloads have no project.path_with_namespace at all, and a project
        // event's own path is unstable across rename/transfer. They must land on the workspace's
        // organization filter `gitlab.<accountLogin>.?.>` (WorkspaceNatsSubscriptionProvider#organizationFilter):
        // the namespace token is the ROOT group segment and the project slot is the placeholder. Without
        // this they derive `gitlab.?.?.<event>` (matches no filter) or a repo-scoped subject that no
        // monitored-repo filter catches on create/rename — i.e. a silent drop.
        if (isGroupTierEvent(event)) {
            return SUBJECT_PREFIX + unreserved(rootGroupToken(payload)) + "." + PLACEHOLDER + "." + event;
        }

        String pathWithNs = firstNonBlank(
                textOrEmpty(payload.path("project"), "path_with_namespace"),
                textOrEmpty(payload, "path_with_namespace"));

        String namespace = PLACEHOLDER;
        String project = PLACEHOLDER;
        if (pathWithNs != null) {
            List<String> parts = sanitizeParts(pathWithNs);
            if (parts.size() >= 2) {
                namespace = String.join("~", parts.subList(0, parts.size() - 1));
                project = parts.get(parts.size() - 1);
            } else if (parts.size() == 1) {
                namespace = parts.get(0);
            }
        }

        return SUBJECT_PREFIX + unreserved(namespace) + "." + project + "." + event;
    }

    /** The subject a delivery authenticated for {@code connectionId} is published on, whatever its payload path. */
    public String deriveConnectionSubject(long connectionId, JsonNode payload) {
        return SUBJECT_PREFIX + CONNECTION_TOKEN + "." + connectionId + "." + eventToken(payload);
    }

    private static String eventToken(JsonNode payload) {
        String rawEvent = textOrEmpty(payload, "object_kind");
        if (rawEvent.isEmpty()) {
            rawEvent = textOrEmpty(payload, "event_name");
        }
        String event = normalizeEvent(rawEvent);
        return event.isEmpty() ? UNKNOWN_TOKEN : event;
    }

    private static String unreserved(String namespace) {
        return CONNECTION_TOKEN.equals(namespace) ? PLACEHOLDER : namespace;
    }

    @Override
    public String deriveDedupKey(byte[] body, Map<String, String> headers) {
        Map<String, String> lower = lowercaseKeys(headers);
        String idempotency = lower.get(HEADER_IDEMPOTENCY_KEY);
        if (idempotency != null && !idempotency.isBlank()) {
            return "gitlab-" + idempotency.trim();
        }
        String eventUuid = lower.get(HEADER_EVENT_UUID);
        if (eventUuid != null && !eventUuid.isBlank()) {
            return "gitlab-" + eventUuid.trim();
        }
        // Fall back: SHA-256 over body + a vendor event marker. The event marker
        // comes from headers (X-Gitlab-Event) when present; otherwise empty. Truncate
        // the hex digest to 32 chars — the full 64 still fits NATS but bloats logs.
        String event = lower.getOrDefault("x-gitlab-event", "");
        byte[] digest = sha256(body, event.getBytes(StandardCharsets.UTF_8));
        String hex = HexFormat.of().formatHex(digest);
        return "gitlab-" + hex.substring(0, Math.min(32, hex.length()));
    }

    /**
     * The dedup key of a delivery authenticated for {@code connectionId}. Every hook of one connection — an old and a new
     * key generation during a rotation, say — sends the same event with the same event UUID and body but its own
     * delivery id, so the key ignores the delivery id and the generation; two connections each keep their copy, and
     * recursive events that share an event UUID keep theirs through the body.
     */
    public String deriveConnectionDedupKey(long connectionId, byte[] body, Map<String, String> headers) {
        Map<String, String> lower = lowercaseKeys(headers);
        String marker =
                lower.getOrDefault(HEADER_EVENT_UUID, "").trim() + "|" + lower.getOrDefault("x-gitlab-event", "");
        String hex = HexFormat.of().formatHex(sha256(body, marker.getBytes(StandardCharsets.UTF_8)));
        return "gitlab-c" + connectionId + "-" + hex.substring(0, 32);
    }

    private static byte[] sha256(byte[] body, byte[] suffix) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(body);
            md.update((byte) '|');
            md.update(suffix);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JRE; if it's missing, fail loudly.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sanitizeEvent(String value) {
        if (value == null || value.isEmpty()) return "";
        // Lowercase + replace dots so the event component never collides with NATS
        // token boundaries.
        return value.toLowerCase(Locale.ROOT).replace('.', '~');
    }

    /**
     * Normalizes the raw discriminator into the wire event token. {@code object_kind} values
     * ({@code merge_request}, {@code issue}, {@code note}, …) pass through sanitized. GitLab's
     * group-tier events instead arrive via {@code event_name} with a granular verb —
     * {@code project_create}, {@code subgroup_destroy}, {@code user_add_to_group} — which we fold
     * onto the stable keys the handlers register under ({@link GitLabEventType#PROJECT},
     * {@link GitLabEventType#SUBGROUP}, {@link GitLabEventType#MEMBER}). This is the single point
     * of normalization the handler javadocs already promise; registering the handlers under the raw
     * verbs instead would scatter 3–5 synonyms per handler and still leave the subject token
     * unnormalized (so a dispatcher round-trip could not agree on one key).
     */
    private static String normalizeEvent(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("project_")) {
            return GitLabEventType.PROJECT.getValue();
        }
        if (lower.startsWith("subgroup_")) {
            return GitLabEventType.SUBGROUP.getValue();
        }
        // user_add_to_group / user_remove_from_group / user_update_for_group → "member".
        // Project-member verbs (user_*_to_team) end with "_team", so they never fold to "member".
        if (lower.startsWith("user_") && lower.endsWith("_group")) {
            return GitLabEventType.MEMBER.getValue();
        }
        return sanitizeEvent(raw);
    }

    private static boolean isGroupTierEvent(String event) {
        return (GitLabEventType.PROJECT.getValue().equals(event)
                || GitLabEventType.SUBGROUP.getValue().equals(event)
                || GitLabEventType.MEMBER.getValue().equals(event));
    }

    /**
     * Root (top-level) group segment for an org-scoped subject, matching the single-token
     * {@code accountLogin} the workspace subscribes with via {@code organizationFilter}. Tries the
     * richest path the group-tier payloads carry — a project's {@code path_with_namespace}, a
     * subgroup's {@code full_path}/{@code parent_full_path}, a member event's {@code group_path} —
     * then takes the first path segment (sanitizing dots to {@code ~} exactly as
     * {@link #sanitizeParts}). Falls back to the {@code ?} placeholder when no path is present.
     *
     * <p><b>Known limitation:</b> member events on a <em>subgroup</em> carry only the leaf
     * {@code group_path} (GitLab sends {@code group.path}, not the full path), so their root segment
     * is the subgroup itself, not the workspace root group — those remain unroutable from payload
     * alone and are healed by the periodic group-member sync. Member events on the root group route
     * correctly.
     */
    private static String rootGroupToken(JsonNode payload) {
        String path = firstNonBlank(
                textOrEmpty(payload.path("project"), "path_with_namespace"),
                textOrEmpty(payload, "path_with_namespace"),
                textOrEmpty(payload.path("group"), "full_path"),
                textOrEmpty(payload, "full_path"),
                textOrEmpty(payload, "parent_full_path"),
                textOrEmpty(payload, "group_path"));
        if (path == null) {
            return PLACEHOLDER;
        }
        List<String> parts = sanitizeParts(path);
        return parts.isEmpty() ? PLACEHOLDER : parts.get(0);
    }

    private static List<String> sanitizeParts(String path) {
        List<String> out = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (!segment.isEmpty()) {
                // Case-preserving: the consumer-side filter (ConsumerSubjectMath#buildSubjectPrefix)
                // is fed the stored, case-preserved RepositoryToMonitor#nameWithOwner, so the producer
                // MUST preserve case too — lowercasing here drops events for mixed-case GitLab paths.
                out.add(segment.replace('.', '~'));
            }
        }
        return out;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString("");
    }

    private static @Nullable String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static Map<String, String> lowercaseKeys(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return out;
    }
}
