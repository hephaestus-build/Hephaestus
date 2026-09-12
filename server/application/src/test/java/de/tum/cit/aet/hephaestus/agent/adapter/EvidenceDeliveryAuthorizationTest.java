package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.AdmittedObservationFixtures;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EvidenceDeliveryAuthorizationTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rejectsDeliveryWhenCurrentSourceAuthorizationWasWithdrawn() {
        AgentJobRepository jobs = mock(AgentJobRepository.class);
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        UUID jobId = UUID.randomUUID();
        when(jobs.findEvidenceContractVersion(jobId, 7L)).thenReturn(Optional.of("1.1.0"));
        when(catalogs.isSourceUsePermitted(
                        new SourceContractVersion("1.1.0"),
                        new SourceKind("scm.pull-request.diff"),
                        SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(false);

        boolean permitted = new EvidenceDeliveryAuthorization(jobs, catalogs)
                .permits(
                        7L,
                        jobId,
                        MAPPER.readTree("{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\"}]}"),
                        SourceUsePurpose.CONVERSATIONAL_MENTORING);

        assertThat(permitted).isFalse();
    }

    @Test
    void permitsCanonicalEvidenceForTheRequestedAudience() {
        AgentJobRepository jobs = mock(AgentJobRepository.class);
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        UUID jobId = UUID.randomUUID();
        when(jobs.findEvidenceContractVersion(jobId, 7L)).thenReturn(Optional.of("1.1.0"));
        when(catalogs.isSourceUsePermitted(
                        new SourceContractVersion("1.1.0"),
                        new SourceKind("scm.pull-request.diff"),
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .thenReturn(true);

        assertThat(new EvidenceDeliveryAuthorization(jobs, catalogs)
                        .permits(
                                7L,
                                jobId,
                                MAPPER.readTree("{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\"}]}"),
                                SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();
    }

    @Test
    void rejectsMissingOrMalformedCitationSources() {
        AgentJobRepository jobs = mock(AgentJobRepository.class);
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        EvidenceDeliveryAuthorization authorization = new EvidenceDeliveryAuthorization(jobs, catalogs);
        UUID malformedJobId = UUID.randomUUID();
        when(jobs.findEvidenceContractVersion(malformedJobId, 7L)).thenReturn(Optional.of("1.1.0"));

        assertThat(authorization.permits(7L, (UUID) null, (JsonNode) null, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        assertThat(authorization.permits(
                        7L,
                        UUID.randomUUID(),
                        MAPPER.readTree("{\"citations\":[]}"),
                        SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        assertThat(authorization.permits(
                        7L,
                        malformedJobId,
                        MAPPER.readTree("{\"citations\":[{\"sourceKind\":7}]}"),
                        SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
    }

    /**
     * The batched answer must equal the per-row answer: a batch admitting what the row form denies would
     * publish evidence nobody may cite, invisibly to any UI.
     *
     * <p>The set mixes both ways a contract version can fail to arrive — no row for the run, and a row
     * whose snapshot recorded none — since the single-row form collapses both into an empty
     * {@link Optional} and a batch that mapped a missing key to anything but "denied" would only fail here.
     */
    @Test
    void batchedAuthorizationDecidesEveryObservationTheWayTheSingleRowFormDoes() {
        AgentJobRepository jobs = mock(AgentJobRepository.class);
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        EvidenceDeliveryAuthorization authorization = new EvidenceDeliveryAuthorization(jobs, catalogs);

        Observation permittedSource = observation("scm.pull-request.diff");
        Observation deniedSource = observation("hephaestus.observation-history");
        Observation runMissing = observation("scm.pull-request.diff");
        Observation snapshotless = observation("scm.pull-request.diff");
        List<Observation> observations = List.of(permittedSource, deniedSource, runMissing, snapshotless);

        when(jobs.findEvidenceContractVersions(eq(7L), any()))
                .thenReturn(List.of(
                        new ContractRow(permittedSource.getAgentJobId(), "1.1.0"),
                        new ContractRow(deniedSource.getAgentJobId(), "1.1.0"),
                        new ContractRow(snapshotless.getAgentJobId(), null)));
        when(jobs.findEvidenceContractVersion(permittedSource.getAgentJobId(), 7L))
                .thenReturn(Optional.of("1.1.0"));
        when(jobs.findEvidenceContractVersion(deniedSource.getAgentJobId(), 7L)).thenReturn(Optional.of("1.1.0"));
        when(jobs.findEvidenceContractVersion(runMissing.getAgentJobId(), 7L)).thenReturn(Optional.empty());
        when(jobs.findEvidenceContractVersion(snapshotless.getAgentJobId(), 7L)).thenReturn(Optional.empty());
        when(catalogs.isSourceUsePermitted(
                        new SourceContractVersion("1.1.0"),
                        new SourceKind("scm.pull-request.diff"),
                        SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(true);
        when(catalogs.isSourceUsePermitted(
                        new SourceContractVersion("1.1.0"),
                        new SourceKind("hephaestus.observation-history"),
                        SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(false);

        Set<UUID> batched = authorization.permitsAll(7L, observations, SourceUsePurpose.CONVERSATIONAL_MENTORING);

        assertThat(batched).containsExactly(permittedSource.getId());
        for (Observation observation : observations) {
            assertThat(batched.contains(observation.getId()))
                    .as("batched verdict for job %s", observation.getAgentJobId())
                    .isEqualTo(authorization.permits(7L, observation, SourceUsePurpose.CONVERSATIONAL_MENTORING));
        }
        verify(jobs).findEvidenceContractVersions(eq(7L), any());
    }

    /**
     * An observation never persisted has no id to hand back, so membership cannot say "permitted" for
     * it; denying is the safe direction, matching every read surface's own assumption that it authorizes
     * rows it loaded.
     */
    @Test
    void deniesAnObservationThatWasNeverPersisted() {
        AgentJobRepository jobs = mock(AgentJobRepository.class);
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        Observation transientObservation = Observation.builder()
                .agentJobId(UUID.randomUUID())
                .evidence(MAPPER.readTree("{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\"}]}"))
                .build();

        assertThat(new EvidenceDeliveryAuthorization(jobs, catalogs)
                        .permitsAll(7L, List.of(transientObservation), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isEmpty();
        verifyNoInteractions(jobs);
    }

    @Test
    void shouldRequireVerifiedCitationsOnlyForNewDelivery() {
        AgentJobRepository jobs = mock(AgentJobRepository.class);
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        var observation = observation("scm.pull-request.diff");
        UUID jobId = observation.getAgentJobId();
        when(jobs.findEvidenceContractVersions(eq(7L), any())).thenReturn(List.of(new ContractRow(jobId, "1.1.0")));
        when(catalogs.isSourceUsePermitted(any(), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenReturn(true);
        var authorization = new EvidenceDeliveryAuthorization(jobs, catalogs);
        assertThat(authorization.permitsAll(7L, List.of(observation), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .containsExactly(observation.getId());
        assertThat(authorization.permitsForNewDelivery(
                        7L, List.of(observation), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
        Observation verified = Observation.builder()
                .id(observation.getId())
                .agentJobId(jobId)
                .evidence(AdmittedObservationFixtures.evidence(jobId, "scm.pull-request.diff"))
                .build();
        assertThat(authorization.permitsForNewDelivery(
                        7L, List.of(verified), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .containsExactly(verified.getId());
    }

    private static Observation observation(String sourceKind) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .evidence(MAPPER.readTree("{\"citations\":[{\"sourceKind\":\"" + sourceKind + "\"}]}"))
                .build();
    }

    private record ContractRow(UUID id, @Nullable String contractVersion)
            implements AgentJobRepository.EvidenceContractVersionRow {
        @Override
        public int getAttempt() {
            return 0;
        }

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        @Nullable
        public String getContractVersion() {
            return contractVersion;
        }
    }
}
