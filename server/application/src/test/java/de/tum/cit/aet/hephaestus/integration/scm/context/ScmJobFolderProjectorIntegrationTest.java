package de.tum.cit.aet.hephaestus.integration.scm.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection.Format;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection.ProjectedRecord;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ScmJobFolderProjectorIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Set<SourceKind> CORE =
            Set.of(new SourceKind("scm.issue.core"), new SourceKind("scm.pull-request.core"));
    private static final Instant DAY = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired
    private ScmJobFolderProjector projector;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    private final AtomicLong nativeIds = new AtomicLong(238_500);

    private User author;
    private Workspace workspace;

    @BeforeEach
    void seedWorkspace() {
        author = persistUser("label-author");
        workspace = createWorkspace("labels", "Labels", "labels-org", AccountType.ORG, author);
    }

    @Test
    void shouldProjectStoredLabelsInCodePointOrderWhenIssuesAndPullRequestsAreRecorded() {
        Repository widgets = repository("labels-org/widgets", workspace);
        Label bug = label("bug", widgets);
        issue(widgets, 1, label("area: api", widgets), bug, label("Documentation", widgets));
        issue(widgets, 2);
        pullRequest(widgets, 3, bug);

        List<ProjectedRecord> records = project(workspace, widgets);

        assertThat(records)
                .extracting(ProjectedRecord::path)
                .containsExactly(
                        "issues/1/record.json",
                        "issues/1/description.md",
                        "issues/2/record.json",
                        "issues/2/description.md",
                        "pulls/3/record.json",
                        "pulls/3/description.md");
        assertThat(records)
                .filteredOn(record -> record.format() == Format.JSON)
                .extracting(record -> record.value().path("labels").toString())
                .as("the same order as the captured issue context, and an explicit empty array when unlabelled")
                .containsExactly("[\"Documentation\",\"area: api\",\"bug\"]", "[]", "[\"bug\"]");
    }

    @Test
    void shouldProjectNoRecordsWhenTheWorkspaceDoesNotMonitorTheRepository() {
        User otherOwner = persistUser("other-owner");
        Workspace other = createWorkspace("other", "Other", "other-org", AccountType.ORG, otherOwner);
        Repository widgets = repository("labels-org/widgets", workspace);
        Repository elsewhere = repository("labels-org/elsewhere", other);
        issue(widgets, 1, label("bug", widgets));
        issue(elsewhere, 1, label("bug", elsewhere));

        assertThat(project(other, widgets)).isEmpty();
        assertThat(project(workspace, elsewhere)).isEmpty();
        assertThat(project(workspace, widgets))
                .extracting(ProjectedRecord::path)
                .containsExactly("issues/1/record.json", "issues/1/description.md");
    }

    private List<ProjectedRecord> project(Workspace target, Repository repository) {
        List<ProjectedRecord> records = new ArrayList<>();
        projector.forEachRecord(target.getId(), repository.getId(), CORE, records::add);
        return records;
    }

    private Repository repository(String nameWithOwner, Workspace monitoredBy) {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(ensureGitHubProvider());
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://github.com/" + nameWithOwner);
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(monitoredBy);
        monitor.setNameWithOwner(nameWithOwner);
        repositoryToMonitorRepository.save(monitor);
        return repository;
    }

    private Label label(String name, Repository repository) {
        Label label = new Label();
        label.setNativeId(nativeIds.incrementAndGet());
        label.setProvider(ensureGitHubProvider());
        label.setName(name);
        label.setColor("0e8a16");
        label.setRepository(repository);
        return labelRepository.save(label);
    }

    private void issue(Repository repository, int number, Label... labels) {
        Issue issue = new Issue();
        issue.setNativeId(nativeIds.incrementAndGet());
        issue.setProvider(ensureGitHubProvider());
        issue.setNumber(number);
        issue.setTitle("Issue " + number);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/" + number);
        issue.setRepository(repository);
        issue.setAuthor(author);
        issue.setCreatedAt(DAY);
        issue.setUpdatedAt(DAY);
        issue.getLabels().addAll(List.of(labels));
        issueRepository.save(issue);
    }

    private void pullRequest(Repository repository, int number, Label... labels) {
        PullRequest pullRequest = new PullRequest();
        pullRequest.setNativeId(nativeIds.incrementAndGet());
        pullRequest.setProvider(ensureGitHubProvider());
        pullRequest.setNumber(number);
        pullRequest.setTitle("Pull request " + number);
        pullRequest.setState(Issue.State.OPEN);
        pullRequest.setHtmlUrl(repository.getHtmlUrl() + "/pull/" + number);
        pullRequest.setRepository(repository);
        pullRequest.setAuthor(author);
        pullRequest.setCreatedAt(DAY);
        pullRequest.setUpdatedAt(DAY);
        pullRequest.getLabels().addAll(List.of(labels));
        pullRequestRepository.save(pullRequest);
    }
}
