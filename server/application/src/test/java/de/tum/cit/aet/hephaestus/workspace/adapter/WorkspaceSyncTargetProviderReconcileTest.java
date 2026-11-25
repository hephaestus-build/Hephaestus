package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.VisibilityClass;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.ResourceReaderRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link WorkspaceSyncTargetProvider#reconcileSyncTargetIdentity}, the correctness
 * backstop that re-keys a monitor's {@code nameWithOwner} by its stable native id after an upstream
 * rename/transfer and refreshes the workspace's inbox consumer so events under the new name are
 * delivered instead of silently ACK-dropped.
 */
class WorkspaceSyncTargetProviderReconcileTest extends BaseUnitTest {

    private static final long SYNC_TARGET_ID = 42L;
    private static final long WORKSPACE_ID = 1L;
    private static final long NATIVE_ID = 555L;
    private static final long PROVIDER_ID = 7L;
    private static final String OLD_NAME = "acme/old-widgets";
    private static final String NEW_NAME = "acme/new-widgets";

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private WorkspaceScopeFilter workspaceScopeFilter;

    @Mock
    private ConnectionService connectionService;

    private Workspace workspace;

    private WorkspaceSyncTargetProvider provider;

    @BeforeEach
    @SuppressWarnings("DirectInvocationOnMock") // The locking query answers from the repository's own stubs.
    void setUp() {
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        provider = new WorkspaceSyncTargetProvider(
                workspaceRepository,
                repositoryToMonitorRepository,
                mock(ResourceReaderRepository.class),
                workspaceScopeFilter,
                connectionService,
                mock(ReviewScopeRepositoryRepository.class),
                mock(ApplicationEventPublisher.class),
                new SimpleMeterRegistry(),
                mock(PrivacyJournal.class));
        // The provider locks a monitor through the entity manager; it answers with what the repository stub holds.
        EntityManager entityManager = mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<RepositoryToMonitor> locking = mock(TypedQuery.class, RETURNS_SELF);
        AtomicReference<Long> lockedId = new AtomicReference<>();
        lenient()
                .when(entityManager.createQuery(anyString(), eq(RepositoryToMonitor.class)))
                .thenReturn(locking);
        lenient().when(locking.setParameter(eq("id"), any())).thenAnswer(invocation -> {
            lockedId.set(invocation.getArgument(1));
            return locking;
        });
        lenient()
                .when(locking.getResultList())
                .thenAnswer(invocation -> repositoryToMonitorRepository.findById(lockedId.get()).stream()
                        .toList());
        ReflectionTestUtils.setField(provider, "entityManager", entityManager);
    }

    private RepositoryToMonitor monitor(Long nativeId, String nameWithOwner) {
        RepositoryToMonitor rtm = new RepositoryToMonitor();
        rtm.setNativeId(nativeId);
        rtm.setNameWithOwner(nameWithOwner);
        rtm.setWorkspace(workspace);
        return rtm;
    }

    @Test
    void shouldRekeyNameAndRefreshConsumerWhenIdAgreesButNameChanged() {
        RepositoryToMonitor rtm = monitor(NATIVE_ID, OLD_NAME);
        when(repositoryToMonitorRepository.findById(SYNC_TARGET_ID)).thenReturn(Optional.of(rtm));

        provider.reconcileSyncTargetIdentity(SYNC_TARGET_ID, NATIVE_ID, NEW_NAME);

        assertThat(rtm.getNameWithOwner()).isEqualTo(NEW_NAME);
        verify(repositoryToMonitorRepository).saveAndFlush(rtm);
    }

    /** The monitor held another provider instance's id and now takes this repository's: the old proof is not its. */
    @Test
    void shouldForgetTheReadersProofWhenTheMonitorTakesAnotherRepositorysId() {
        RepositoryToMonitor rtm = monitor(111L, OLD_NAME);
        ReflectionTestUtils.setField(rtm, "id", SYNC_TARGET_ID);
        rtm.getReaders().confirmedPublic(rtm.getReaders().getProofGeneration(), Instant.now());
        when(repositoryToMonitorRepository.findById(SYNC_TARGET_ID)).thenReturn(Optional.of(rtm));
        when(repositoryToMonitorRepository.monitorHoldsAnotherInstancesId(SYNC_TARGET_ID, NATIVE_ID))
                .thenReturn(true);

        provider.reconcileSyncTargetIdentity(SYNC_TARGET_ID, NATIVE_ID, OLD_NAME);

        assertThat(rtm.getNativeId()).isEqualTo(NATIVE_ID);
        assertThat(rtm.getReaders().getVisibilityClass()).isEqualTo(VisibilityClass.UNPROVEN);
    }

    @Test
    void shouldNotRenameWhenStableIdDisagrees() {
        RepositoryToMonitor rtm = monitor(111L, OLD_NAME);
        when(repositoryToMonitorRepository.findById(SYNC_TARGET_ID)).thenReturn(Optional.of(rtm));

        provider.reconcileSyncTargetIdentity(SYNC_TARGET_ID, 222L, NEW_NAME);

        // A different id means a different repository — never rename by name alone.
        assertThat(rtm.getNameWithOwner()).isEqualTo(OLD_NAME);
        verify(repositoryToMonitorRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRekeyEveryMonitorSharingTheRepositoryWhenHealingFromAWebhook() {
        // A repository can be monitored by several tenants at once; a rename webhook names no sync
        // target, so the stable native id is the only handle onto all of them.
        RepositoryToMonitor first = monitor(NATIVE_ID, OLD_NAME);
        first.setId(SYNC_TARGET_ID);
        RepositoryToMonitor second = monitor(NATIVE_ID, OLD_NAME);
        second.setId(SYNC_TARGET_ID + 1);
        when(repositoryToMonitorRepository.findByNativeIdOnSourceProvider(NATIVE_ID, PROVIDER_ID))
                .thenReturn(List.of(first, second));
        when(repositoryToMonitorRepository.findById(SYNC_TARGET_ID)).thenReturn(Optional.of(first));
        when(repositoryToMonitorRepository.findById(SYNC_TARGET_ID + 1)).thenReturn(Optional.of(second));

        provider.reconcileSyncTargetsForRepository(PROVIDER_ID, NATIVE_ID, NEW_NAME);

        assertThat(first.getNameWithOwner()).isEqualTo(NEW_NAME);
        assertThat(second.getNameWithOwner()).isEqualTo(NEW_NAME);
    }

    @Test
    void shouldNotRekeyByNameWhenTheWebhookCarriesNoStableId() {
        provider.reconcileSyncTargetsForRepository(PROVIDER_ID, null, NEW_NAME);

        verify(repositoryToMonitorRepository, never()).findByNativeIdOnSourceProvider(any(), any());
        verify(repositoryToMonitorRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldBeNoOpWhenSyncTargetMissing() {
        when(repositoryToMonitorRepository.findById(SYNC_TARGET_ID)).thenReturn(Optional.empty());

        provider.reconcileSyncTargetIdentity(SYNC_TARGET_ID, NATIVE_ID, NEW_NAME);

        verify(repositoryToMonitorRepository, never()).saveAndFlush(any());
    }
}
