package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckoutException;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckouts;
import de.tum.cit.aet.hephaestus.integration.scm.context.MentorContextQueryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.context.ScmMentorWorkQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a repository of the workspace into Heph's context folder, at {@code repos/<host>/<full path>/}: a checkout at
 * the resolved ref, with the Git history that the ref reaches and no credential ({@link RepositoryCheckouts}). The
 * workspace sees a repository only when its connection is active, it monitors the repository, and no team hides it
 * from contributions. Every other repository, of this or another workspace, gets the same refusal.
 */
@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ScmContextLoader implements ContextLoader {

    /** How many repositories {@code INDEX.md} lists, and how many open pull requests per repository. */
    static final int LISTED_REPOSITORIES = 40;

    static final int LISTED_PULL_REQUESTS = 10;

    private static final Pattern REVIEW_REF = Pattern.compile("(pull|merge-request)/[1-9][0-9]{0,8}");

    private final RepositoryCheckouts checkouts;
    private final ReviewRepositoryPreparer preparer;
    private final WorkspaceTeamRepositorySettingsRepository teamRepositorySettings;
    private final PullRequestRepository pullRequests;
    private final ScmMentorWorkQuery work;
    private final MentorContextQueryRepository authoredWork;

    /** Repositories the situation names, and authored pull requests it looks at to rank them. */
    static final int SITUATION_REPOSITORIES = 5;

    static final int SITUATION_AUTHORED = 20;

    /**
     * Arguments: the default branch. Run in a loaded review's directory: the merge base, the head's last 5 commits and
     * the changed files against the merge base, at most 40 of them.
     */
    static final String REVIEW_SUMMARY = """
            export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0='*'
            b=$(git merge-base HEAD "refs/heads/$1" 2>/dev/null) || exit 0
            echo "Merge base with $1: $(git rev-parse --short=12 "$b")"
            echo "Last commits of the head:"
            git log -5 --format='%h %s' HEAD
            echo "Changed files against the merge base (git diff --stat $(git rev-parse --short=12 "$b") HEAD):"
            git diff --stat=120 "$b" HEAD | awk '{ if (NR > 1 && NR - 1 <= 40) print prev; prev = $0 }
              END { if (NR - 1 > 40) print "… " NR - 1 - 40 " more files"; if (NR > 0) print prev }'
            """;

    @Override
    public String prefix() {
        return RepositoryLoadPath.PREFIX;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> destinations(long workspaceId, long accountId) {
        Optional<String> host = host(workspaceId);
        if (host.isEmpty() || !checkouts.enabled()) return List.of();
        return visible(workspaceId).stream()
                .map(repository -> RepositoryLoadPath.PREFIX + host.get() + "/" + repository.getNameWithOwner())
                .toList();
    }

    @Override
    public String index(long workspaceId, long accountId) {
        Optional<String> host = host(workspaceId);
        List<Repository> visible = visible(workspaceId);
        if (host.isEmpty() || visible.isEmpty() || !checkouts.enabled()) {
            return "";
        }
        String review = review(visible.getFirst().getProvider().kind());
        StringBuilder section = new StringBuilder("## repos/\n\n")
                .append("Repositories that `load_context` loads here, with their Git history. A load without a ref")
                .append(" takes the default branch. Add `@<branch>`, `@<commit sha>`");
        if (review != null) {
            section.append(" or `@").append(review).append("/<number>`");
        }
        section.append(" for another version. Then read and search the files, and use `git log`, `git show`,")
                .append(" `git diff` and `git blame` in the repository's directory.\n\n");
        visible.stream()
                .sorted(Comparator.comparing(Repository::getNameWithOwner))
                .limit(LISTED_REPOSITORIES)
                .forEach(repository -> {
                    section.append("- `")
                            .append(RepositoryLoadPath.PREFIX)
                            .append(host.get())
                            .append('/')
                            .append(repository.getNameWithOwner())
                            .append('`');
                    String defaultBranch = repository.getDefaultBranch();
                    if (defaultBranch != null) {
                        section.append(", default branch `")
                                .append(defaultBranch)
                                .append('`');
                    }
                    List<Integer> open = pullRequests.findNumbersByRepositoryIdAndState(
                            repository.getId(), Issue.State.OPEN, Limit.of(LISTED_PULL_REQUESTS));
                    if (!open.isEmpty() && review != null) {
                        section.append(", open: ")
                                .append(open.stream()
                                        .map(number -> "#" + number)
                                        .collect(Collectors.joining(", ")));
                    }
                    section.append('\n');
                });
        if (visible.size() > LISTED_REPOSITORIES) {
            section.append("- and ")
                    .append(visible.size() - LISTED_REPOSITORIES)
                    .append(" more\n");
        }
        return section.toString();
    }

    /**
     * The repositories of the developer's most recent pull requests first, then the others, at most
     * {@link #SITUATION_REPOSITORIES}: each with its default branch and the developer's open reviews.
     */
    @Override
    @Transactional(readOnly = true)
    public String situation(long workspaceId, long accountId) {
        Optional<String> host = host(workspaceId);
        List<Repository> visible = visible(workspaceId);
        if (host.isEmpty() || visible.isEmpty() || !checkouts.enabled()) {
            return "";
        }
        String review = review(visible.getFirst().getProvider().kind());
        Map<String, Repository> byName = new LinkedHashMap<>();
        visible.stream()
                .sorted(Comparator.comparing(Repository::getNameWithOwner))
                .forEach(repository -> byName.put(repository.getNameWithOwner(), repository));
        Map<String, List<Integer>> open = new LinkedHashMap<>();
        work.actors(workspaceId, accountId)
                .filter(actors -> !actors.userIds().isEmpty())
                .map(actors -> authoredWork.findRecentAuthoredPullRequests(
                        workspaceId, actors.providerId(), actors.userIds(), PageRequest.of(0, SITUATION_AUTHORED)))
                .orElse(List.of())
                .forEach(pullRequest -> {
                    Repository repository = pullRequest.getRepository();
                    if (repository == null || !byName.containsKey(repository.getNameWithOwner())) return;
                    List<Integer> numbers =
                            open.computeIfAbsent(repository.getNameWithOwner(), ignored -> new ArrayList<>());
                    if (pullRequest.getState() == Issue.State.OPEN) numbers.add(pullRequest.getNumber());
                });
        byName.keySet().forEach(name -> open.putIfAbsent(name, List.of()));
        StringBuilder line =
                new StringBuilder("Repositories you can load now, their recent work first (INDEX.md lists all):");
        int listed = 0;
        for (var entry : open.entrySet()) {
            if (listed == SITUATION_REPOSITORIES) break;
            Repository repository = byName.get(entry.getKey());
            StringBuilder item = new StringBuilder(" ")
                    .append(RepositoryLoadPath.PREFIX)
                    .append(host.get())
                    .append('/')
                    .append(entry.getKey());
            String defaultBranch = repository == null ? null : repository.getDefaultBranch();
            List<String> details = new ArrayList<>();
            if (defaultBranch != null) details.add("default " + defaultBranch);
            if (review != null && !entry.getValue().isEmpty()) {
                details.add("your open "
                        + entry.getValue().stream()
                                .map(number -> "@" + review + "/" + number)
                                .collect(Collectors.joining(" ")));
            }
            if (!details.isEmpty())
                item.append(" (").append(String.join("; ", details)).append(')');
            item.append(';');
            if (line.length() + item.length() > ContextLoader.SITUATION_CHARS) break;
            line.append(item);
            listed++;
        }
        return listed == 0 ? "" : line.substring(0, line.length() - 1) + ".";
    }

    @Override
    public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
        RepositoryLoadPath parsed = RepositoryLoadPath.parse(path).orElseThrow(() -> notVisible(path));
        if (!host(workspaceId).map(parsed.host()::equals).orElse(false)) {
            throw notVisible(path);
        }
        Repository repository = visible(workspaceId).stream()
                .filter(candidate -> candidate.getNameWithOwner().equals(parsed.repository()))
                .findFirst()
                .orElseThrow(() -> notVisible(path));
        String asked = parsed.ref();
        String defaultBranch = repository.getDefaultBranch();
        String ref;
        if (asked != null) {
            ref = asked;
        } else if (defaultBranch != null) {
            ref = defaultBranch;
        } else {
            throw new ContextLoadException(
                    ContextLoadException.Code.REF_NOT_FOUND,
                    parsed.folderPath() + " has no known default branch. Load it with @<branch>.");
        }
        RepositoryCheckouts.Checkout checkout;
        try {
            // The default branch comes along as a local branch, so that `git diff <default>...HEAD` works as in a
            // clone. No other branch, tag or review ref does.
            String base = defaultBranch == null || defaultBranch.equals(ref) ? null : defaultBranch;
            checkout = checkouts.checkout(workspaceId, parsed.repository(), ref, base, abandoned);
        } catch (RepositoryCheckoutException refused) {
            ContextLoadException.Code code = switch (refused.code()) {
                case REPOSITORY_NOT_VISIBLE -> ContextLoadException.Code.NOT_VISIBLE;
                case REF_NOT_FOUND -> ContextLoadException.Code.REF_NOT_FOUND;
                case MIRROR_UNAVAILABLE -> ContextLoadException.Code.UNAVAILABLE;
            };
            String text = switch (code) {
                case NOT_VISIBLE -> notVisibleText(path);
                case REF_NOT_FOUND -> "No commit for " + ref + " in " + parsed.folderPath() + ".";
                case UNAVAILABLE -> "The repository cannot be loaded now. Do not retry in this answer.";
            };
            throw new ContextLoadException(code, text, refused);
        }
        String sha = checkout.head();
        String cite = "@" + sha.substring(0, 7);
        String folderPath = parsed.folderPath();
        return new Loaded() {
            @Override
            public String path() {
                return folderPath;
            }

            @Override
            public Path directory() {
                return checkout.directory();
            }

            @Override
            public Version version() {
                return new Version(ref, sha, cite);
            }

            @Override
            public String note() {
                String baseSha = checkout.base();
                String branches = "`" + ref + "` at " + sha.substring(0, 12)
                        + (baseSha == null || defaultBranch == null
                                ? ""
                                : ", and the default branch `" + defaultBranch + "` at " + baseSha.substring(0, 12));
                return "Loaded " + folderPath + " at " + ref + ", with the Git history that these commits reach."
                        + " Local branches: " + branches + ". HEAD is " + sha.substring(0, 12)
                        + ". To read another branch, tag or commit, load it: " + folderPath + "@<name>."
                        + " Cite its code as <file path in the repository>:<line>" + cite + ".";
            }

            @Override
            public List<String> summary() {
                boolean review = REVIEW_REF.matcher(ref).matches();
                return review && defaultBranch != null && checkout.base() != null
                        ? List.of("sh", "-c", REVIEW_SUMMARY, "summary", defaultBranch)
                        : List.of();
            }

            @Override
            public void close() {
                checkout.close();
            }
        };
    }

    /** The repositories the workspace may read: monitored on an active connection, and hidden by no team. */
    private List<Repository> visible(long workspaceId) {
        Set<Long> hidden = Set.copyOf(teamRepositorySettings.findHiddenRepositoryIdsByWorkspace(workspaceId));
        return preparer.permittedRepositories(workspaceId).stream()
                .filter(repository -> !hidden.contains(repository.getId()))
                .toList();
    }

    /** The host of the workspace's provider server, as the folder names it. */
    private Optional<String> host(long workspaceId) {
        return preparer.activeTokenSource(workspaceId)
                .flatMap(source -> source.serverUrl(workspaceId))
                .flatMap(ScmContextLoader::hostOf);
    }

    static Optional<String> hostOf(String serverUrl) {
        try {
            String host = URI.create(serverUrl).getHost();
            return Optional.ofNullable(host).map(name -> name.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static @Nullable String review(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> "pull";
            case GITLAB -> "merge-request";
            default -> null;
        };
    }

    private static ContextLoadException notVisible(String path) {
        return new ContextLoadException(ContextLoadException.Code.NOT_VISIBLE, notVisibleText(path));
    }

    /** One text for every repository the workspace cannot load, so that none can be told apart. */
    private static String notVisibleText(String path) {
        return "No repository at " + path + " that this workspace can load. INDEX.md lists the repositories you can"
                + " load.";
    }
}
