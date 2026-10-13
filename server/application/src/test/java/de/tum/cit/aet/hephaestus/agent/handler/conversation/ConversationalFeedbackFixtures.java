package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedObservationFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * One practice of a workspace, review runs that observed it in a developer's work, and the conversational feedback
 * prepared from them: what Heph's {@code link_observation} can show.
 */
public final class ConversationalFeedbackFixtures {

    private static final ObjectMapper OM = new ObjectMapper();

    private final ApplicationContext beans;
    private final Workspace workspace;
    private final Practice practice;

    /** Turns Silent Mode off, so feedback can be prepared and delivered. */
    public ConversationalFeedbackFixtures(ApplicationContext beans, Workspace workspace, String practiceName) {
        this.beans = beans;
        this.workspace = workspace;
        InstanceSettingsService settings = beans.getBean(InstanceSettingsService.class);
        settings.updateSilentMode(
                false,
                null,
                null,
                EntityTagPrecondition.parse("\"" + settings.get().getVersion() + "\""));
        PracticeRepository practices = beans.getBean(PracticeRepository.class);
        Practice created = new Practice();
        PracticeTestEvidence.configure(created, ArtifactKinds.PULL_REQUEST);
        created.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        created.setWorkspace(workspace);
        created.setSlug("test-practice");
        created.setName(practiceName);
        created.setCriteria("Test description");
        created.setAutonomy(PracticeAutonomy.AUTOMATIC);
        PracticeTestEvidence.configure(created, ScmSignals.PULL_REQUEST_OPENED);
        created = practices.saveAndFlush(created);
        PracticeRevision revision =
                beans.getBean(PracticeRevisionRepository.class).save(new PracticeRevision(created, 1));
        created.setCurrentRevision(revision);
        this.practice = practices.saveAndFlush(created);
    }

    public AgentJob job() {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OM.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OM.readTree("{\"manifest\":{\"contractVersion\":\"1.2.0\"}}"));
        return beans.getBean(AgentJobRepository.class).save(job);
    }

    /** An observation of the practice in a pull request of {@code about}, citing its description. */
    public Observation observation(AgentJob job, User about, String occurrenceKey) {
        return observation(job, about, occurrenceKey, "scm.pull-request.core");
    }

    /** An observation of the practice in a pull request of {@code about}, citing evidence of {@code sourceKind}. */
    public Observation observation(AgentJob job, User about, String occurrenceKey, String sourceKind) {
        ObservationRepository observations = beans.getBean(ObservationRepository.class);
        UUID id = UUID.randomUUID();
        observations.insertIfAbsent(
                id,
                occurrenceKey,
                job.getId(),
                workspace.getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                "scm.pull_request",
                42L,
                about.getId(),
                "Observation title",
                "NOT_MET",
                "MAJOR",
                AdmittedObservationFixtures.evidence(
                                job.getId(), sourceKind, "context/metadata.json", "metadata.json", "example")
                        .toString(),
                null,
                null,
                Instant.now(),
                "LIVE");
        return observations.findById(id).orElseThrow();
    }

    /** Prepares the run's conversational feedback, as the review's delivery does. */
    public void prepare(AgentJob job) {
        List<Observation> admitted = beans.getBean(FeedbackChannelRouter.class)
                .admit(
                        beans.getBean(ObservationRepository.class).findByAgentJobId(job.getId(), workspace.getId()),
                        workspace.getId(),
                        RoutingContext.author());
        beans.getBean(ConversationalFeedbackPreparer.class)
                .prepare(job.getId(), workspace.getId(), admitted, List.of(conversationUnit(admitted)));
    }

    /** The workspace's conversational feedback, in any state. */
    public List<Feedback> conversationUnits() {
        return beans.getBean(EntityManager.class)
                .createQuery(
                        "SELECT f FROM Feedback f WHERE f.workspaceId = :workspaceId AND f.channel = :channel",
                        Feedback.class)
                .setParameter("workspaceId", workspace.getId())
                .setParameter("channel", FeedbackChannel.IN_CHAT)
                .getResultList();
    }

    private ComposedFeedbackUnit conversationUnit(List<Observation> observations) {
        return new ComposedFeedbackUnit(
                FeedbackChannel.IN_CHAT,
                practice.getSlug(),
                observations.stream().map(o -> o.getId().toString()).toList(),
                ComposedFeedbackUnit.Action.NEW,
                null,
                null,
                practice.getName(),
                null,
                null,
                new ComposedFeedbackUnit.ConversationBrief(
                        "The practice recurred.",
                        "Recognize the decision point.",
                        "The observations show the same pattern.",
                        "They can explain the decision in their own words.",
                        null));
    }
}
