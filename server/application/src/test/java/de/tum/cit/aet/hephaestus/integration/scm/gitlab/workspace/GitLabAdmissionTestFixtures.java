package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.RepositoryReads;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.mockito.ArgumentMatchers;

/** Explicit original permits for unit tests. SQL authority is tested in the integration tier. */
public final class GitLabAdmissionTestFixtures {
    private GitLabAdmissionTestFixtures() {}

    public static GitLabProjectAdmission.ConnectionRead connection(
            GitLabProjectAdmission admission, long workspaceId, long providerId, String group) {
        var read = new GitLabProjectAdmission.ConnectionRead(1L, workspaceId, providerId, group, Map.of());
        lenient().when(admission.captureConnection(workspaceId, providerId)).thenReturn(Optional.of(read));
        lenient()
                .when(admission.beginRepositoryReads(read))
                .thenReturn(new RepositoryReads(workspaceId, providerId, Instant.EPOCH, Map.of()));
        return read;
    }

    public static void discovery(
            GitLabProjectAdmission admission,
            GitLabProjectAdmission.ConnectionRead read,
            long nativeId,
            String path,
            String serverUrl) {
        lenient()
                .doAnswer(invocation -> {
                    Supplier<?> write = invocation.getArgument(4);
                    return Optional.ofNullable(write.get());
                })
                .when(admission)
                .discover(
                        eq(read),
                        eq(nativeId),
                        eq(path),
                        eq(serverUrl),
                        ArgumentMatchers.<Supplier<@Nullable Object>>any());
    }

    public static GitLabProjectAdmission.ProjectRead permit(
            GitLabProjectAdmission admission, long workspaceId, Repository repository, long monitorId) {
        Long suppliedNativeId = repository.getNativeId();
        long nativeId = suppliedNativeId == null ? repository.getId() : suppliedNativeId;
        long repositoryId = repository.getId();
        String path = repository.getNameWithOwner();
        var connection = new GitLabProjectAdmission.ConnectionRead(
                1L, workspaceId, 1L, path.split("/", -1)[0], Map.of("id:" + nativeId, Set.of(monitorId)));
        var read = new GitLabProjectAdmission.ProjectRead(connection, repositoryId, nativeId, monitorId);
        lenient()
                .when(admission.capture(
                        eq(workspaceId),
                        argThat(candidate -> candidate != null
                                && candidate.getId() == repositoryId
                                && Objects.equals(candidate.getNameWithOwner(), path))))
                .thenReturn(Optional.of(read));
        lenient().doReturn(true).when(admission).hold(read);
        lenient().doAnswer(invocation -> admission.hold(read)).when(admission).current(read);
        lenient()
                .doAnswer(invocation -> {
                    if (!admission.hold(read)) return false;
                    Runnable write = invocation.getArgument(1);
                    write.run();
                    return true;
                })
                .when(admission)
                .runWrite(eq(read), any());
        lenient()
                .doAnswer(invocation -> {
                    if (!admission.hold(read)) return Optional.empty();
                    Supplier<?> write = invocation.getArgument(1);
                    return Optional.ofNullable(write.get());
                })
                .when(admission)
                .write(eq(read), ArgumentMatchers.<Supplier<@Nullable Object>>any());
        return read;
    }
}
