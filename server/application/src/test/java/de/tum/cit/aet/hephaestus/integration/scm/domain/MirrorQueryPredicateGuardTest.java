package de.tum.cit.aet.hephaestus.integration.scm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.context.MentorContextQueryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Guards the ways a mirror query silently counts or returns rows it should not.
 *
 * <p><b>Why this asserts on the query text rather than on returned rows.</b> The defects live
 * entirely in JPQL semantics, and neither tier that could execute JPQL catches them cheaply: unit
 * tests mock the repository interface, so a mock returns whatever the test stubs regardless of what
 * the {@code @Query} says, and the integration tier needs a live Postgres. The predicates are
 * therefore verified where they are actually written. A missing predicate fails here, in the tier
 * every contributor runs first, instead of surfacing as a wrong number in the product.
 */
class MirrorQueryPredicateGuardTest extends BaseUnitTest {

    /** Matches a JPQL {@code FROM Issue <alias>} range declaration, including inside a subquery. */
    private static final Pattern ISSUE_RANGE = Pattern.compile("\\bFROM\\s+Issue\\s+(\\w+)\\b");

    /**
     * Matches any JPQL range declaration over a tombstonable artifact or one of its children.
     * Longest-first alternation so {@code PullRequestReviewThread} is never read as
     * {@code PullRequest}. {@code JOIN} declares a range as much as {@code FROM} does — this tree
     * already writes entity joins with {@code ON} — while an association join ({@code JOIN p.author},
     * {@code JOIN FETCH p.reviews}) never matches the entity-name alternation.
     */
    private static final Pattern ARTIFACT_RANGE = Pattern.compile("\\b(?:FROM|JOIN)\\s+(PullRequestReviewThread"
            + "|PullRequestReviewComment|PullRequestReview|IssueComment|PullRequest|Issue)\\s+(\\w+)\\b");

    /**
     * The path from a range's alias to the {@code deleted_at} column that decides whether its rows are
     * live. A review, review comment or thread carries no tombstone of its own — it goes away with the
     * pull request it hangs off — so the parent's column is the only signal there is.
     */
    private static final Map<String, String> TOMBSTONE_PATH_BY_ENTITY = Map.of(
            "PullRequestReviewThread", ".pullRequest.deletedAt IS NULL",
            "PullRequestReviewComment", ".pullRequest.deletedAt IS NULL",
            "PullRequestReview", ".pullRequest.deletedAt IS NULL",
            "IssueComment", ".issue.deletedAt IS NULL",
            "PullRequest", ".deletedAt IS NULL",
            "Issue", ".deletedAt IS NULL");

    @Nested
    @DisplayName("mentor context")
    class MentorContext {

        /**
         * Hephaestus answers about the developer's own work, so a pull request or issue deleted upstream must
         * not reach it — the mentor would describe something the developer cannot open.
         *
         * <p>Scanned per range declaration rather than per method name: a whole-query substring passes a
         * query that has two ranges and one predicate, and a method-name list goes quiet the moment a
         * tenth query is added. Every range over an artifact or one of its children must carry its own
         * predicate, so a new unguarded mentor query fails here without anyone remembering to extend a
         * list.
         */
        @Test
        void everyMentorArtifactRangeExcludesTombstones() {
            assertEveryRangeHasLivePredicate(MentorContextQueryRepository.class, Set.of());
        }

        /**
         * The monitor matches a repository by path alone, so each monitored range also needs the connected
         * instance, or a same-path project on another instance reaches Hephaestus. A repository a team hides from
         * contributions stays out of every list, as it does in Activity and the review history.
         */
        @Test
        void everyMonitoredMentorRangeIsOnTheConnectedInstanceAndNotHidden() {
            Map<String, String> offenders = new LinkedHashMap<>();
            for (Method method : MentorContextQueryRepository.class.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || query.nativeQuery()) {
                    continue;
                }
                String jpql = normalize(query.value());
                int monitors = countOccurrences(jpql, "JOIN RepositoryToMonitor ");
                if (countOccurrences(jpql, "wtrs.hiddenFromContributions = true") < monitors) {
                    offenders.put(method.getName(), "the hidden-repository exclusion");
                } else if (countOccurrences(jpql, ".provider.id = :providerId") < monitors) {
                    offenders.put(method.getName(), "the connected-instance predicate");
                }
            }

            assertThat(offenders)
                    .as("every monitored mentor range must be on the connected instance and not hidden. "
                            + "Offending method -> the predicate it is missing")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("project inventory")
    class ProjectInventory {

        @Test
        void bothProjectInventoryQueriesExcludeTombstones() {
            Map<String, String> offenders = new LinkedHashMap<>();

            collectUnguardedRanges(
                    "findIssueInventoryByRepositoryId",
                    queryOf(IssueRepository.class, "findIssueInventoryByRepositoryId"),
                    offenders);
            collectUnguardedRanges(
                    "findPullRequestInventoryByRepositoryId",
                    queryOf(PullRequestRepository.class, "findPullRequestInventoryByRepositoryId"),
                    offenders);

            assertThat(offenders)
                    .as("the project inventory must exclude tombstones, or a review is handed an index of work "
                            + "that is no longer in the project. Offending method -> the predicate it is missing")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("single-table discriminator")
    class Discriminator {

        /**
         * {@code Issue} and {@code PullRequest} share one table under single-table inheritance, so a
         * JPQL query rooted at {@code Issue} without {@code TYPE(i) = Issue} also returns merge
         * requests. On a GitLab workspace that makes the mentor list a merge request as an assigned
         * open issue — and disagrees with the typed open-issue count in the same repository.
         *
         * <p>Scanned generically over every {@code @Query} in the interface (subqueries included) so a
         * newly added issue query inherits the guard rather than needing a new test.
         */
        @Test
        void everyIssueRootedMentorQueryRestrictsToTheIssueDiscriminator() {
            Map<String, String> offenders = new LinkedHashMap<>();

            for (Method method : MentorContextQueryRepository.class.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || query.nativeQuery()) {
                    continue;
                }
                String jpql = normalize(query.value());
                for (String alias : issueAliases(jpql)) {
                    String required = "TYPE(" + alias + ")=Issue";
                    if (!jpql.replace(" ", "").contains(required)) {
                        offenders.put(method.getName(), alias);
                    }
                }
            }

            assertThat(offenders)
                    .as("JPQL rooted at Issue without TYPE(<alias>) = Issue also returns PullRequest rows "
                            + "(single-table inheritance) — a GitLab merge request would surface as an issue. "
                            + "Offending method -> unguarded alias")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("tombstoned-parent exclusion")
    class TombstonedParents {

        /**
         * Sub-entity rows carry no tombstone of their own — they go away with the issue or pull
         * request they hang off, so the parent's {@code deletedAt} is the only signal there is.
         * Counting children of a tombstoned parent reintroduces on the child row exactly the
         * permanent inflation the deletion sweep removes from the parent: the admin would watch an
         * issue count fall while its comment count stayed put.
         *
         * <p>Commits are deliberately absent: they hang off the repository directly, not off a
         * tombstonable issue or pull request, so there is no parent tombstone to honour.
         */
        @Test
        void everySyncStatusSubEntityCountExcludesChildrenOfTombstonedParents() {
            record Guarded(Class<?> repository, String parentPath) {}

            List<Guarded> guarded = List.of(
                    new Guarded(IssueCommentRepository.class, "c.issue"),
                    new Guarded(PullRequestReviewRepository.class, "r.pullRequest"),
                    new Guarded(PullRequestReviewCommentRepository.class, "c.pullRequest"));

            for (Guarded entry : guarded) {
                String jpql = normalize(queryOf(entry.repository(), "countGroupedByRepositoryIds"));

                assertThat(jpql)
                        .as(
                                "%s.countGroupedByRepositoryIds must exclude rows whose parent is tombstoned "
                                        + "(%s.deletedAt IS NULL), consistent with the issue and pull-request counts",
                                entry.repository().getSimpleName(), entry.parentPath())
                        .contains(entry.parentPath() + ".deletedAt IS NULL");
            }
        }

        /**
         * The exclusion must not cost a query. Each predicate rides the parent association the
         * grouping already joins, so the read model stays at one grouped statement per entity class
         * for the whole connection rather than one per repository.
         */
        @Test
        void theExclusionStaysInsideTheExistingGroupedQuery() {
            String jpql = normalize(queryOf(IssueCommentRepository.class, "countGroupedByRepositoryIds"));

            assertThat(jpql).contains("GROUP BY");
            assertThat(countOccurrences(jpql, "SELECT"))
                    .as("exactly one SELECT: the grouped count, with no correlated subquery added by the tombstone"
                            + " filter")
                    .isEqualTo(1);
        }
    }

    private static String queryOf(Class<?> repository, String methodName) {
        for (Method method : repository.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            Query query = method.getAnnotation(Query.class);
            assertThat(query)
                    .as("%s.%s must carry an explicit @Query", repository.getSimpleName(), methodName)
                    .isNotNull();
            return query.value();
        }
        throw new AssertionError(repository.getSimpleName() + " declares no " + methodName);
    }

    /**
     * Fails for every artifact range whose alias carries no live predicate, and for every query the
     * scan cannot read that the caller has not excused by name.
     */
    private static void assertEveryRangeHasLivePredicate(Class<?> repository, Set<String> unreadableButReviewed) {
        Map<String, String> offenders = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();

        for (Method method : repository.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query == null || query.nativeQuery()) {
                if (!unreadableButReviewed.contains(method.getName())) {
                    unreadable.add(method.getName());
                }
                continue;
            }
            collectUnguardedRanges(method.getName(), query.value(), offenders);
        }

        assertThat(offenders)
                .as(
                        "%s: every issue or pull-request range must exclude tombstones, or Hephaestus describes work "
                                + "the developer can no longer open. Offending method -> the predicate it is missing",
                        repository.getSimpleName())
                .isEmpty();
        assertThat(unreadable)
                .as(
                        "%s: a derived or native query cannot be scanned for a tombstone predicate. Name it in the "
                                + "reviewed set once it is known to reach no issue or pull-request row",
                        repository.getSimpleName())
                .isEmpty();
    }

    /** Records, per artifact range the JPQL declares, the live predicate its alias is missing. */
    private static void collectUnguardedRanges(String methodName, String rawJpql, Map<String, String> offenders) {
        String jpql = normalize(rawJpql);
        Matcher matcher = ARTIFACT_RANGE.matcher(jpql);
        while (matcher.find()) {
            String alias = matcher.group(2);
            String required = alias + TOMBSTONE_PATH_BY_ENTITY.get(matcher.group(1));
            // Anchored on a word boundary rather than a bare substring: an alias that is a suffix of
            // another alias in the same query would otherwise be excused by the other one's predicate.
            if (!Pattern.compile("(?<![\\w.])" + Pattern.quote(required))
                    .matcher(jpql)
                    .find()) {
                offenders.put(methodName + " (" + alias + ")", required);
            }
        }
    }

    private static List<String> issueAliases(String jpql) {
        List<String> aliases = new ArrayList<>();
        Matcher matcher = ISSUE_RANGE.matcher(jpql);
        while (matcher.find()) {
            aliases.add(matcher.group(1));
        }
        return aliases;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = at + needle.length();
        }
        return count;
    }

    /** Collapses the whitespace a text block adds so predicates match regardless of line wrapping. */
    private static String normalize(String jpql) {
        return jpql.replaceAll("\\s+", " ").trim();
    }
}
