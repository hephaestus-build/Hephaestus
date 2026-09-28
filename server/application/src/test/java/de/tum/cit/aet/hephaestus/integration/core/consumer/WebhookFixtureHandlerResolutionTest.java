package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineSubjectParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.webhook.GithubSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.scm.github.webhook.GithubSubjectParser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitlabSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitlabSubjectParser;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackSubjectParser;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Mechanical dispatcher round-trip guard. For every committed webhook fixture whose event the system
 * <em>claims to handle</em>, the real routing pipeline
 * (fixture payload → {@code SubjectKeyDeriver} → {@code SubjectParser} → {@link EventTypeKey} →
 * handler-registry lookup) MUST resolve a registered handler. A key that no handler owns is
 * ACK-dropped silently at DEBUG by {@code IntegrationNatsConsumer} — an invisible data-loss bug. This
 * test makes that whole class fail loudly at build time and names the offending fixture.
 *
 * <p>Unlike per-side unit tests (deriver-only, parser-only), and unlike {@link SubjectGrammarRoundTripTest}
 * (which only proves the publisher subject falls under the consumer's <em>subscription filter</em>), this
 * closes the last link: the parsed {@link EventTypeKey} actually resolves a handler bean. This is the seam
 * that lets an event be handled on one tier yet dead on another — e.g. {@code repository.team} (permission
 * events keyed to a tier with no handler) or GitLab group events.
 *
 * <p><b>Ground truth without a Spring context.</b> The set of registered keys is read straight from the
 * production handler classes: every concrete {@link IntegrationMessageHandler} under the kind's package
 * is reflectively constructed with null collaborators (their constructors only assign fields; the
 * {@code EventTypeKey} is built from compile-time literals in the {@code super(...)} call) and its
 * {@link IntegrationMessageHandler#key()} recorded. No database, no beans — but zero drift from reality.
 *
 * <p><b>"Claims to handle".</b> A fixture is only required to resolve when its event token is the suffix
 * of some registered key — i.e. the system genuinely handles that event type on <em>some</em> tier.
 * Fixtures for events we deliberately ignore (e.g. {@code deployment}, {@code star}, {@code watch}) have
 * no handler on any tier and are skipped, so this never produces a false failure. The bug it catches is
 * the <em>partial</em> case: an event handled on one tier but silently dropped on another (exactly
 * {@code team}: {@code organization.team} handled, {@code repository.team} was not).
 *
 * <p><b>All four integrations are covered.</b> GitHub keeps the original {@link #resolutionFailures}
 * helper (its {@code X-GitHub-Event} is sourced from the fixture filename, and some filenames encode a
 * payload variant rather than a bare event token, so the claimed-token skip is the right fit there).
 * GitLab, Slack, and Outline read their event token straight from the payload
 * ({@code object_kind}/{@code event_name}, the bolt {@code event.type}, the dotted {@code event}
 * respectively), so they run through the stricter {@link #assertFixturesResolveOrAllowlisted} helper:
 * every committed fixture must EITHER resolve a registered handler OR appear on an explicit, justified
 * allowlist. That closes the last hole the claimed-token skip leaves open — an event handled on
 * <em>no</em> tier is no longer silently skipped; it must be either handled or consciously allowlisted,
 * so a newly-committed fixture for an unhandled event fails the build until someone decides.
 */
class WebhookFixtureHandlerResolutionTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GITHUB_HANDLER_PACKAGE = "de.tum.cit.aet.hephaestus.integration.scm.github";
    private static final Path GITHUB_FIXTURE_DIR = Paths.get("src/test/resources/github");

    private static final GithubSubjectKeyDeriver GITHUB_DERIVER = new GithubSubjectKeyDeriver();
    private static final GithubSubjectParser GITHUB_PARSER = new GithubSubjectParser();

    private static final String GITLAB_HANDLER_PACKAGE = "de.tum.cit.aet.hephaestus.integration.scm.gitlab";
    private static final Path GITLAB_FIXTURE_DIR = Paths.get("src/test/resources/gitlab");
    private static final GitlabSubjectKeyDeriver GITLAB_DERIVER = new GitlabSubjectKeyDeriver();
    private static final GitlabSubjectParser GITLAB_PARSER = new GitlabSubjectParser();

    private static final String SLACK_HANDLER_PACKAGE = "de.tum.cit.aet.hephaestus.integration.slack";
    private static final Path SLACK_FIXTURE_DIR = Paths.get("src/test/resources/slack");
    private static final SlackSubjectKeyDeriver SLACK_DERIVER = new SlackSubjectKeyDeriver();
    private static final SlackSubjectParser SLACK_PARSER = new SlackSubjectParser();

    private static final String OUTLINE_HANDLER_PACKAGE = "de.tum.cit.aet.hephaestus.integration.outline";
    private static final Path OUTLINE_FIXTURE_DIR = Paths.get("src/test/resources/outline");
    private static final OutlineSubjectKeyDeriver OUTLINE_DERIVER = new OutlineSubjectKeyDeriver(MAPPER);
    private static final OutlineSubjectParser OUTLINE_PARSER = new OutlineSubjectParser();

    /**
     * GitLab events we <em>receive</em> (a fixture is committed) but deliberately do not mirror, so no
     * handler is registered on any tier. Each stays an explicit, justified ACK-drop rather than a silent
     * skip: a new fixture for any other unhandled event fails the build until it is either handled or
     * added here with a reason. Keyed by the wire event token the deriver emits.
     */
    private static final Map<String, String> GITLAB_ACK_DROP_ALLOWLIST = Map.of(
            "emoji",
            "award/revoke emoji-reaction events — Hephaestus does not model reactions.",
            "feature_flag",
            "feature-flag toggles — outside the mirrored SCM domain.",
            "release",
            "GitLab releases are not mirrored yet (GitLabEventType javadoc: add a handler when needed).",
            "tag_push",
            "tag pushes are not mirrored — only branch pushes drive sync; TAG_PUSH has no handler by design.",
            "wiki_page",
            "wiki pages are not part of the mirrored SCM domain.");

    /** Slack has no committed webhook fixtures today; every registered bolt event has a handler. */
    private static final Map<String, String> SLACK_ACK_DROP_ALLOWLIST = Map.of();

    /** Every Outline event collapses onto the single {@code document} handler key, so none is ever dropped. */
    private static final Map<String, String> OUTLINE_ACK_DROP_ALLOWLIST = Map.of();

    @Test
    void everyClaimedGithubFixtureResolvesAHandler() throws IOException {
        Set<EventTypeKey> registeredKeys = registeredKeys(GITHUB_HANDLER_PACKAGE);

        // Guard-health: if the registry came back empty the reflective scan silently broke and every
        // assertion below would vacuously pass. Anchor on the two team tiers.
        assertThat(registeredKeys)
                .as("GitHub handler registry must be discoverable and non-empty")
                .isNotEmpty()
                .contains(new EventTypeKey(IntegrationKind.GITHUB, "organization.team"))
                .contains(new EventTypeKey(IntegrationKind.GITHUB, "repository.team"));

        // Event tokens the system claims to handle = the last segment of every registered key
        // ("repository.issues" -> "issues", "organization.team" -> "team").
        Set<String> claimedEventTokens = registeredKeys.stream()
                .map(k -> lastSegment(k.eventType()))
                .collect(Collectors.toCollection(TreeSet::new));

        List<String> failures = resolutionFailures(
                GITHUB_FIXTURE_DIR,
                registeredKeys,
                claimedEventTokens,
                WebhookFixtureHandlerResolutionTest::githubEventToken,
                (payload, eventToken) -> GITHUB_PARSER.parse(
                        GITHUB_DERIVER.deriveSubject(payload, Map.of("X-GitHub-Event", eventToken))));

        assertThat(failures)
                .as("Every committed GitHub webhook fixture for a claimed event must round-trip to a "
                        + "registered handler. A listed fixture derives a subject whose parsed EventTypeKey has "
                        + "no handler — the consumer would ACK-drop it silently (invisible data loss).")
                .isEmpty();
    }

    /** A value without a handler is a subscription whose deliveries are ACK-dropped. */
    @Test
    void shouldRegisterAHandlerForExactlyTheGithubEventTypes() {
        Set<String> handledEvents = registeredKeys(GITHUB_HANDLER_PACKAGE).stream()
                .map(key -> lastSegment(key.eventType()))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> eventTypes = Arrays.stream(GitHubEventType.values())
                .map(GitHubEventType::getValue)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(handledEvents)
                .as("GitHubEventType and the registered GitHub handlers must name the same events")
                .isEqualTo(eventTypes);
    }

    @Test
    void everyGitlabFixtureResolvesOrIsAllowlisted() throws IOException {
        Set<EventTypeKey> registeredKeys = registeredKeys(GITLAB_HANDLER_PACKAGE);
        assertThat(registeredKeys)
                .as("GitLab handler registry must be discoverable and non-empty")
                .isNotEmpty()
                .contains(new EventTypeKey(IntegrationKind.GITLAB, "merge_request"))
                // Anchors the group-tier routing (project/subgroup/member).
                .contains(new EventTypeKey(IntegrationKind.GITLAB, "project"))
                .contains(new EventTypeKey(IntegrationKind.GITLAB, "member"));

        assertFixturesResolveOrAllowlisted(
                "GitLab",
                GITLAB_FIXTURE_DIR,
                registeredKeys,
                payload -> GITLAB_PARSER.parse(GITLAB_DERIVER.deriveSubject(payload, Map.of())),
                GITLAB_ACK_DROP_ALLOWLIST);
    }

    @Test
    void everySlackFixtureResolvesOrIsAllowlisted() throws IOException {
        Set<EventTypeKey> registeredKeys = registeredKeys(SLACK_HANDLER_PACKAGE);
        assertThat(registeredKeys)
                .as("Slack handler registry must be discoverable and non-empty")
                .isNotEmpty()
                .contains(new EventTypeKey(IntegrationKind.SLACK, "message"))
                .contains(new EventTypeKey(IntegrationKind.SLACK, "message_im"));

        // No Slack webhook fixtures are committed today, so this block is vacuous now but becomes active
        // the instant a Slack fixture lands: a fixture for a bolt event with no handler will fail the
        // build unless it is handled or explicitly allowlisted, mirroring the SCM/Outline invariant.
        assertFixturesResolveOrAllowlisted(
                "Slack",
                SLACK_FIXTURE_DIR,
                registeredKeys,
                payload -> SLACK_PARSER.parse(SLACK_DERIVER.deriveSubject(payload, Map.of())),
                SLACK_ACK_DROP_ALLOWLIST);
    }

    @Test
    void everyOutlineFixtureResolvesOrIsAllowlisted() throws IOException {
        Set<EventTypeKey> registeredKeys = registeredKeys(OUTLINE_HANDLER_PACKAGE);
        assertThat(registeredKeys)
                .as("Outline handler registry must be discoverable and non-empty")
                .isNotEmpty()
                .contains(new EventTypeKey(IntegrationKind.OUTLINE, "document"));

        // Outline's parser folds every event onto the single `document` key, so every committed fixture
        // must resolve. This guards that collapse (and that each fixture yields a well-formed 3-part
        // subject the parser accepts) for the whole committed corpus.
        assertFixturesResolveOrAllowlisted(
                "Outline",
                OUTLINE_FIXTURE_DIR,
                registeredKeys,
                payload -> OUTLINE_PARSER.parse(OUTLINE_DERIVER.deriveSubject(payload, Map.of())),
                OUTLINE_ACK_DROP_ALLOWLIST);
    }

    // --- pipeline ---

    /** Payload-driven kinds derive their event token from the body, so no header/filename is needed. */
    @FunctionalInterface
    private interface KeyForPayload {
        EventTypeKey apply(JsonNode payload);
    }

    /**
     * Stricter sibling of {@link #resolutionFailures}: for a payload-driven integration, every committed
     * fixture must EITHER resolve a registered handler OR carry an event token on the explicit
     * {@code allowlist}. Three outcomes fail the build, each naming the fixture:
     * <ul>
     *   <li><b>parser rejection</b> — a received payload could not even be turned into a routable key;</li>
     *   <li><b>partial drop</b> — the event is handled on <em>some</em> tier (its token is claimed) but the
     *       derived key resolves no handler (the {@code repository.team} bug class); never allowlist these;</li>
     *   <li><b>unhandled &amp; not allowlisted</b> — no handler anywhere and no conscious allowlist entry.</li>
     * </ul>
     * It also fails on a <b>stale</b> allowlist entry no fixture exercises, so a fixed gap or removed
     * fixture cannot leave a dead justification behind.
     */
    private static void assertFixturesResolveOrAllowlisted(
            String kindLabel,
            Path fixtureDir,
            Set<EventTypeKey> registeredKeys,
            KeyForPayload keyForPayload,
            Map<String, String> allowlist)
            throws IOException {
        Set<String> claimedEventTokens = registeredKeys.stream()
                .map(k -> lastSegment(k.eventType()))
                .collect(Collectors.toCollection(TreeSet::new));

        List<String> failures = new ArrayList<>();
        Set<String> allowlistTokensSeen = new TreeSet<>();

        List<Path> fixtures;
        if (!Files.isDirectory(fixtureDir)) {
            fixtures = List.of();
        } else {
            try (var stream = Files.list(fixtureDir)) {
                fixtures = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList();
            }
        }

        for (Path fixture : fixtures) {
            String name = fixture.getFileName().toString();
            JsonNode payload = MAPPER.readTree(Files.readAllBytes(fixture));
            EventTypeKey key;
            try {
                key = keyForPayload.apply(payload);
            } catch (RuntimeException e) {
                failures.add(name + " -> deriver/parser rejected the payload: " + e.getMessage());
                continue;
            }
            if (registeredKeys.contains(key)) {
                continue;
            }
            String token = lastSegment(key.eventType());
            if (claimedEventTokens.contains(token)) {
                failures.add(name + " (event="
                        + token
                        + ") -> PARTIAL DROP: this event is handled on another tier but derived key "
                        + key
                        + " resolves no handler. Fix the routing — do NOT allowlist.");
            } else if (allowlist.containsKey(token)) {
                allowlistTokensSeen.add(token);
            } else {
                failures.add(name + " (event="
                        + token
                        + ") -> UNHANDLED on every tier and not allowlisted. Either register a handler or add "
                        + "an explicit allowlist entry justifying the ACK-drop so it can never regress silently.");
            }
        }

        assertThat(failures)
                .as(
                        "Every committed %s webhook fixture must resolve a registered handler or be explicitly "
                                + "allowlisted as an intentional ACK-drop. Offending fixtures below would be dropped "
                                + "silently by IntegrationNatsConsumer (invisible data loss).",
                        kindLabel)
                .isEmpty();

        Set<String> staleAllowlist = new TreeSet<>(allowlist.keySet());
        staleAllowlist.removeAll(allowlistTokensSeen);
        assertThat(staleAllowlist)
                .as(
                        "Stale %s allowlist entries: no committed fixture derives these tokens anymore (a handler "
                                + "was added or the fixture removed). Remove them so the allowlist stays honest.",
                        kindLabel)
                .isEmpty();
    }

    /** Kind-agnostic: derive the {@link EventTypeKey} the routing pipeline would compute for a fixture. */
    @FunctionalInterface
    private interface KeyForFixture {
        EventTypeKey apply(JsonNode payload, String eventToken);
    }

    private static List<String> resolutionFailures(
            Path fixtureDir,
            Set<EventTypeKey> registeredKeys,
            Set<String> claimedEventTokens,
            java.util.function.Function<Path, String> eventTokenOf,
            KeyForFixture keyForFixture)
            throws IOException {
        List<String> failures = new ArrayList<>();
        if (!Files.isDirectory(fixtureDir)) {
            return failures;
        }
        List<Path> fixtures;
        try (var stream = Files.list(fixtureDir)) {
            fixtures = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        for (Path fixture : fixtures) {
            String eventToken = eventTokenOf.apply(fixture);
            // Only events the system claims to handle on some tier are required to resolve.
            if (!claimedEventTokens.contains(eventToken)) {
                continue;
            }
            JsonNode payload = MAPPER.readTree(Files.readAllBytes(fixture));
            EventTypeKey key;
            try {
                key = keyForFixture.apply(payload, eventToken);
            } catch (RuntimeException e) {
                failures.add(fixture.getFileName() + " -> parser rejected derived subject: " + e.getMessage());
                continue;
            }
            if (!registeredKeys.contains(key)) {
                failures.add(fixture.getFileName() + " (event=" + eventToken + ") -> no handler for key " + key);
            }
        }
        return failures;
    }

    /** GitHub's {@code X-GitHub-Event} equals the fixture filename's first dot-delimited segment. */
    private static String githubEventToken(Path fixture) {
        String name = fixture.getFileName().toString();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    // --- registry ground truth (no Spring context) ---

    private static Set<EventTypeKey> registeredKeys(String packageToScan) {
        // Ground truth = the set of handler classes on the classpath, NOT the beans that would be wired
        // in a given profile. GitLab/Slack/Outline handlers are @ConditionalOnProperty/@ConditionalOnServerRole
        // gated, and the default ClassPathScanningCandidateComponentProvider evaluates @Conditional and would
        // silently drop every gated handler (empty registry → the guard passes vacuously). Bypass condition
        // evaluation entirely by matching on assignability alone; abstract bases are still filtered below.
        var typeFilter = new AssignableTypeFilter(IntegrationMessageHandler.class);
        var provider = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.core.type.classreading.MetadataReader mr)
                    throws IOException {
                return typeFilter.match(mr, getMetadataReaderFactory());
            }
        };
        provider.addIncludeFilter(typeFilter);

        Set<EventTypeKey> keys = new java.util.HashSet<>();
        List<String> introspectionFailures = new ArrayList<>();
        for (var candidate : provider.findCandidateComponents(packageToScan)) {
            String className = candidate.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> clazz = Class.forName(className);
                if (Modifier.isAbstract(clazz.getModifiers())
                        || clazz.equals(AbstractIntegrationMessageHandler.class)) {
                    continue;
                }
                IntegrationMessageHandler handler = instantiateForKey(clazz);
                EventTypeKey key = handler.key();
                if (key == null) {
                    introspectionFailures.add(className + " returned null key()");
                } else {
                    keys.add(key);
                }
            } catch (Throwable t) {
                introspectionFailures.add(className + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        // A handler we cannot introspect is a hole in the guard — fail loudly rather than under-cover.
        assertThat(introspectionFailures)
                .as("Every %s handler must be reflectively introspectable to read its routing key", packageToScan)
                .isEmpty();
        return keys;
    }

    /**
     * Construct a handler purely to read {@link IntegrationMessageHandler#key()}. Handler constructors
     * only assign their collaborators to fields and pass compile-time literals to {@code super(...)}, so
     * null collaborators are safe here — {@code key()} depends solely on those literals.
     *
     * <p>One exception: a handler may derive a second {@code REQUIRES_NEW} template in its body via
     * {@code transactionTemplate.getTransactionManager()} (e.g. {@code GitLabMemberMessageHandler}), which
     * NPEs on a null template. Hand any {@link TransactionTemplate} parameter a real (empty) instance —
     * harmless for the handlers that merely stash it, and enough to survive the constructor.
     */
    private static IntegrationMessageHandler instantiateForKey(Class<?> clazz) throws ReflectiveOperationException {
        Constructor<?> ctor = java.util.Arrays.stream(clazz.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new NoSuchMethodException("no constructor on " + clazz.getName()));
        ctor.setAccessible(true);
        Class<?>[] paramTypes = ctor.getParameterTypes();
        Object[] args = new Object[ctor.getParameterCount()];
        for (int i = 0; i < paramTypes.length; i++) {
            if (TransactionTemplate.class.equals(paramTypes[i])) {
                args[i] = new TransactionTemplate(
                        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
            }
        }
        return (IntegrationMessageHandler) ctor.newInstance(args);
    }

    private static String lastSegment(String eventType) {
        int dot = eventType.lastIndexOf('.');
        return dot < 0 ? eventType : eventType.substring(dot + 1);
    }
}
