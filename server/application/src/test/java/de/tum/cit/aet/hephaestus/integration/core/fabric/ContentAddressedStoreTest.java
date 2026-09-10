package de.tum.cit.aet.hephaestus.integration.core.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentAddressedStoreTest extends BaseUnitTest {

    @TempDir
    Path root;

    private ContentAddressedStore cas;

    @BeforeEach
    void setUp() {
        cas = new ContentAddressedStore(new FabricLayout(root.toString()));
    }

    @Test
    void put_isContentAddressed_sameBytesSameSha() {
        String a = cas.put("hello fabric".getBytes(StandardCharsets.UTF_8));
        String b = cas.put("hello fabric".getBytes(StandardCharsets.UTF_8));
        String c = cas.put("different".getBytes(StandardCharsets.UTF_8));
        assertThat(a).hasSize(64).isEqualTo(b);
        assertThat(c).isNotEqualTo(a);
        // The sha-256 of "hello fabric" computed independently (printf | sha256sum), not from the SUT.
        assertThat(a).isEqualTo("3cfda08ddabd9ed87165b733ba29cb0caf9073edf5d3a8517833d16f90b66b41");
    }

    @Test
    void getAndExists_roundTrip() {
        byte[] payload = "diff --git a b".getBytes(StandardCharsets.UTF_8);
        String sha = cas.put(payload);
        assertThat(cas.exists(sha)).isTrue();
        assertThat(cas.get(sha)).contains(payload);
        assertThat(cas.exists("0".repeat(64))).isFalse();
        assertThat(cas.get("0".repeat(64))).isEmpty();
    }

    @Test
    void getRejectsCorruptedBlob() throws Exception {
        String sha = cas.put("original".getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.writeString(cas.pathFor(sha), "corrupted");

        assertThatThrownBy(() -> cas.get(sha))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest mismatch");
    }

    @Test
    void putReusesContentAndRefreshesRetentionAge() throws Exception {
        String sha = cas.put("immutable".getBytes(StandardCharsets.UTF_8));
        Path blob = cas.pathFor(sha);
        var oldTime = java.nio.file.attribute.FileTime.from(java.time.Instant.EPOCH);
        java.nio.file.Files.setLastModifiedTime(blob, oldTime);

        assertThat(cas.put("immutable".getBytes(StandardCharsets.UTF_8))).isEqualTo(sha);
        assertThat(java.nio.file.Files.readString(blob)).isEqualTo("immutable");
        assertThat(java.nio.file.Files.getLastModifiedTime(blob).toMillis()).isGreaterThan(oldTime.toMillis());
    }

    @Test
    void sweep_removesUnreferencedBlobsOnly() {
        String keep = cas.put("keep".getBytes(StandardCharsets.UTF_8));
        String drop = cas.put("drop".getBytes(StandardCharsets.UTF_8));
        int removed = cas.sweep(Set.of(keep));
        assertThat(removed).isEqualTo(1);
        assertThat(cas.exists(keep)).isTrue();
        assertThat(cas.exists(drop)).isFalse();
    }

    @Test
    void sweep_leavesNonBlobFilesUntouched() throws Exception {
        // A stray non-sha file (e.g. an in-flight ".tmp-*.blob" of a racing put) reconstructs to a
        // non-sha key and must NOT be deleted even with an empty live set — guards the put/sweep race.
        String real = cas.put("real".getBytes(StandardCharsets.UTF_8));
        Path fanout = cas.pathFor(real).getParent();
        org.junit.jupiter.api.Assertions.assertNotNull(fanout);
        Path temp = fanout.resolve(".tmp-123.blob");
        java.nio.file.Files.write(temp, "in-flight".getBytes(StandardCharsets.UTF_8));

        int removed = cas.sweep(Set.of()); // nothing live

        assertThat(temp).as("a non-sha temp file is never swept").exists();
        assertThat(cas.exists(real)).as("the real (unreferenced) blob is swept").isFalse();
        assertThat(removed).isEqualTo(1);
    }

    @Test
    void get_returnsEmptyForMissingBlob() throws Exception {
        String sha = cas.put("ephemeral".getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.delete(cas.pathFor(sha));

        assertThat(cas.get(sha)).isEmpty();
    }

    @Test
    void sweep_prunesEmptyFanoutDirectories() {
        String drop = cas.put("solo".getBytes(StandardCharsets.UTF_8));
        Path fanout = cas.pathFor(drop).getParent();
        assertThat(fanout).exists();

        cas.sweep(Set.of());

        assertThat(fanout).as("an emptied fan-out dir is pruned").doesNotExist();
    }

    @Test
    void sweep_keepsFanoutDirectoryThatStillHoldsALiveBlob() {
        String keep = cas.put("retained".getBytes(StandardCharsets.UTF_8));
        Path fanout = cas.pathFor(keep).getParent();

        cas.sweep(Set.of(keep));

        assertThat(fanout).as("a fan-out dir with a live blob is not pruned").exists();
        assertThat(cas.exists(keep)).isTrue();
    }

    @Test
    void put_recreatesMissingFanoutDirectory() throws Exception {
        String sha = cas.put("racy".getBytes(StandardCharsets.UTF_8));
        Path fanout = cas.pathFor(sha).getParent();
        java.nio.file.Files.delete(cas.pathFor(sha));
        java.nio.file.Files.delete(fanout);
        assertThat(fanout).doesNotExist();

        String reput = cas.put("racy".getBytes(StandardCharsets.UTF_8));

        assertThat(reput).isEqualTo(sha);
        assertThat(cas.exists(sha)).isTrue();
    }

    @Test
    void pathFor_rejectsNonSha() {
        assertThatThrownBy(() -> cas.pathFor("not-a-sha")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cas.pathFor("../escape")).isInstanceOf(IllegalArgumentException.class);
    }
}
