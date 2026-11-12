package de.tum.cit.aet.hephaestus.integration.core.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.util.FileSystemUtils;

class EvidenceSpoolTest extends BaseUnitTest {
    @TempDir
    Path root;

    @Test
    void shouldAttributeTheCopyBeforeWritingContentAndPreserveItsLiveLease() throws Exception {
        var spool = new FabricLayout(root.toString()).spool();
        try (var copy = EvidenceSpoolFixtures.directory(spool, 12)) {
            assertThat(copy.path()).startsWith(root.resolve(".evidence-spool/12"));
            assertThat(copy.path().resolveSibling(".lease")).isRegularFile();
            Files.writeString(copy.path().resolve("private.java"), "person's code");
            assertExternallyHeld(copy.path().resolveSibling(".lease"), "before");
            assertThat(new FabricLayout(root.toString()).spool().reconcile()).isZero();
            assertExternallyHeld(copy.path().resolveSibling(".lease"), "after");
            assertThat(copy.path().resolve("private.java")).hasContent("person's code");
        }
    }

    @Test
    void shouldRequireCaptureAdmissionBeforeAllocatingContent() {
        var spool = new FabricLayout(root.toString()).spool();
        assertThatThrownBy(() -> spool.directory(12)).isInstanceOf(IllegalStateException.class);
        assertThat(root.resolve(".evidence-spool")).doesNotExist();
    }

    @Test
    void shouldAllowRepeatedCloseOnlyForAnAbsentEntryInAKnownHome() throws Exception {
        var spool = new FabricLayout(root.toString()).spool();
        var copy = EvidenceSpoolFixtures.directory(spool, 12);
        copy.close();
        EvidenceSpool.close(copy.path());
        EvidenceSpool.close(copy.path());
        Path unknown = root.resolve(".evidence-spool/12/copy-unregistered/content");
        Files.createDirectories(unknown);
        Files.writeString(unknown.resolve("private.java"), "unregistered code");
        assertThatThrownBy(() -> EvidenceSpool.close(unknown)).isInstanceOf(IOException.class);
        assertThat(unknown.resolve("private.java")).hasContent("unregistered code");
        assertThatThrownBy(() -> EvidenceSpool.close(root.resolve("unknown/content")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldBorrowOneAdmissionSessionAndRetainItUntilTheLastCopyIsGone() throws Exception {
        var spool = new FabricLayout(root.toString()).spool();
        var admission = mock(PersonDataCopyFence.Lease.class);
        var acquisitions = new AtomicInteger();
        EvidenceSpool.Copy copy;
        EvidenceSpool.Copy second;
        var outer = spool.capture(12, () -> {
            acquisitions.incrementAndGet();
            return admission;
        });
        try (outer) {
            var inner = spool.capture(12, () -> {
                throw new AssertionError("Nested capture must not acquire another connection");
            });
            try (inner) {
                assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
                copy = spool.directory(12);
                second = spool.directory(12);
                Files.writeString(copy.path().resolve("private.java"), "person's code");
            }
        }
        assertThat(acquisitions).hasValue(1);
        verify(admission, never()).close();
        try (second) {
            Files.writeString(second.path().resolve("private.java"), "second private copy");
            copy.close();
            verify(admission, never()).close();
        }
        verify(admission, times(1)).close();
        assertThat(copy.path()).doesNotExist();
    }

    @Test
    void shouldDetachTheWorkerScopeWhileItsReturnedCopyRetainsAdmission() throws Exception {
        var spool = new FabricLayout(root.toString()).spool();
        var first = mock(PersonDataCopyFence.Lease.class);
        var second = mock(PersonDataCopyFence.Lease.class);
        try (var worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())) {
            var copy = worker.submit(() -> {
                        var scope = spool.capture(12, () -> first);
                        try (scope) {
                            return spool.directory(12);
                        }
                    })
                    .get(30, TimeUnit.SECONDS);
            try (copy) {
                worker.submit(() -> {
                            var next = spool.capture(99, () -> second);
                            try (next) {
                                return spool.reconcile();
                            }
                        })
                        .get(30, TimeUnit.SECONDS);
                verify(second).close();
                verify(first, never()).close();
            }
            verify(first).close();
        }
    }

    @Test
    void shouldRetryFailedRemovalWithoutReleasingItsFileOrAdmissionLease() throws Exception {
        var spool = new FabricLayout(root.toString()).spool();
        var admission = mock(PersonDataCopyFence.Lease.class);
        EvidenceSpool.Copy copy;
        var capture = spool.capture(12, () -> admission);
        try (capture) {
            copy = spool.directory(12);
            Files.writeString(copy.path().resolve("private.java"), "person's code");
        }
        var io = failRemoval(copy.path());
        try (io) {
            assertThatThrownBy(copy::close).isInstanceOf(IOException.class);
            assertThat(copy.path().resolve("private.java")).hasContent("person's code");
            assertThat(spool.reconcile()).isEqualTo(1);
            verify(admission, never()).close();
            assertThatThrownBy(() -> new FabricLayout(root.toString()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before this server starts");
        }
        assertThat(spool.reconcile()).isZero();
        assertThat(copy.path()).doesNotExist();
        verify(admission).close();
        copy.close();
    }

    @Test
    void shouldRejectAnUnregisteredPathWithoutRemovingItsFiles() throws Exception {
        Path unknown = Files.writeString(root.resolve("private.java"), "other owner's code");
        assertThatThrownBy(() -> EvidenceSpool.close(unknown))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no registered owner");
        assertThat(unknown).hasContent("other owner's code");
    }

    @Test
    void shouldRemoveFreshOrphansAndLegacySpoolsBeforeStartupWithoutTouchingReceiptOwnedInputs() throws Exception {
        Path orphan = Files.createDirectories(root.resolve(".evidence-spool/12/copy-orphan/content"));
        Files.createFile(orphan.resolveSibling(".lease"));
        Files.writeString(orphan.resolve("private.java"), "orphaned code");
        Path legacy =
                Files.writeString(root.resolve(EvidenceSpool.PREFIX + "dead-process-inputs.tar"), "old private bytes");
        Path receipt = Files.createDirectories(root.resolve("jobs/12/receipt"));
        Files.writeString(receipt.resolve("private.java"), "receipt-owned code");
        new FabricLayout(root.toString());
        assertThat(orphan.getParent()).doesNotExist();
        assertThat(legacy).doesNotExist();
        assertThat(receipt.resolve("private.java")).hasContent("receipt-owned code");
    }

    @Test
    void shouldRefuseStartupWhenAnOrphanCannotBeRemoved() throws Exception {
        Path entry = Files.createDirectories(root.resolve(".evidence-spool/12/copy-orphan"));
        Files.createFile(entry.resolve(".lease"));
        Files.writeString(entry.resolve("private.java"), "orphaned code");
        var io = failRemoval(entry);
        try (io) {
            assertThatThrownBy(() -> new FabricLayout(root.toString()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before this server starts");
            assertThat(entry.resolve("private.java")).hasContent("orphaned code");
        }
        new FabricLayout(root.toString());
        assertThat(entry).doesNotExist();
    }

    @Test
    void shouldRetainTheFailedCloseUntilReconciliationWithoutReleasingAdmissionTwice() throws Exception {
        var spool = new FabricLayout(root.toString()).spool();
        var admission = mock(PersonDataCopyFence.Lease.class);
        doThrow(new IllegalStateException("session cleanup failed"))
                .when(admission)
                .close();
        EvidenceSpool.Copy copy;
        var capture = spool.capture(12, () -> admission);
        try (capture) {
            copy = spool.directory(12);
            Files.writeString(copy.path().resolve("private.java"), "person's code");
        }
        assertThatThrownBy(copy::close).isInstanceOf(IOException.class);
        assertThat(copy.path()).doesNotExist();
        assertThat(spool.reconcile()).isZero();
        copy.close();
        verify(admission, times(1)).close();
    }

    @Test
    void shouldRefuseStartupWhenTheSpoolCannotBeRead() throws Exception {
        Path entry = Files.createDirectories(root.resolve(".evidence-spool/12/copy-orphan"));
        Files.createFile(entry.resolve(".lease"));
        Path payload = Files.writeString(entry.resolve("private.java"), "orphaned code");
        Path spool = root.resolve(".evidence-spool");
        try (var io = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            io.when(() -> Files.readAttributes(spool, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))
                    .thenThrow(new AccessDeniedException(spool.toString()));
            assertThatThrownBy(() -> new FabricLayout(root.toString()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before this server starts");
            assertThat(payload).hasContent("orphaned code");
        }
        new FabricLayout(root.toString());
        assertThat(entry).doesNotExist();
    }

    @Test
    void shouldRejectAnotherWorkspaceOrEvidenceHomeBeforeCreatingFiles() throws Exception {
        var first = new FabricLayout(root.resolve("first").toString()).spool();
        var second = new FabricLayout(root.resolve("second").toString()).spool();
        var admission = mock(PersonDataCopyFence.Lease.class);
        var scope = first.capture(12, () -> admission);
        try (scope) {
            assertThatThrownBy(() -> first.directory(99)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> second.directory(12)).isInstanceOf(IllegalStateException.class);
            assertThat(root.resolve("first/.evidence-spool/99")).doesNotExist();
            assertThat(root.resolve("second/.evidence-spool/12")).doesNotExist();
        }
        verify(admission).close();
    }

    private static MockedStatic<FileSystemUtils> failRemoval(Path target) {
        return mockStatic(FileSystemUtils.class, invocation -> {
            if (invocation.getMethod().getName().equals("deleteRecursively")
                    && target.equals(invocation.getArgument(0))) throw new IOException("volume unavailable");
            return invocation.callRealMethod();
        });
    }

    private void assertExternallyHeld(Path marker, String phase) throws Exception {
        Path result = root.resolve("lease-" + phase);
        Path output = root.resolve("probe-" + phase + ".log");
        var process = new ProcessBuilder(
                        ProcessHandle.current().info().command().orElseThrow(),
                        "-Xmx64m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        LeaseProbe.class.getName(),
                        marker.toString(),
                        result.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS))
                    .as("Lease probe completed")
                    .isTrue();
            assertThat(process.exitValue()).as(Files.readString(output)).isZero();
            assertThat(result).hasContent("held");
        } finally {
            process.destroyForcibly();
        }
    }

    /** Checks the operating system lock, which an in-process overlapping-lock check cannot prove. */
    public static final class LeaseProbe {
        public static void main(String[] args) throws Exception {
            try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
                var lease = channel.tryLock();
                if (lease == null) {
                    Files.writeString(Path.of(args[1]), "held");
                } else {
                    try (lease) {
                        Files.writeString(Path.of(args[1]), "free");
                    }
                }
            }
        }
    }
}
