package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class PiSdkInstallationTest {

    @TempDir
    Path temp;

    private Path imageSdk(Path root) throws IOException {
        Files.createDirectories(root.resolve("patches/nested"));
        Files.writeString(root.resolve("package.json"), "{\"dependencies\":{}}\n");
        Files.writeString(root.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
        Files.writeString(
                root.resolve("pnpm-workspace.yaml"), "patchedDependencies:\n  a@1.0.0: patches/a@1.0.0.patch\n");
        Files.writeString(root.resolve("patches/a@1.0.0.patch"), "--- a\n+++ b\n");
        Files.writeString(root.resolve("patches/nested/b@2.0.0.patch"), "--- c\n+++ d\n");
        Files.writeString(root.resolve("README.md"), "not an install input\n");
        Files.createDirectories(root.resolve("node_modules/x"));
        Files.writeString(root.resolve("node_modules/x/index.js"), "not an install input\n");
        return root;
    }

    @Test
    void shouldCopyTheManifestAndEveryPatchUnderItsRelativeName() throws IOException {
        Path image = imageSdk(temp.resolve("image"));
        Path install = temp.resolve("install");
        Files.createDirectories(install.resolve("patches"));
        Files.writeString(install.resolve("patches/removed@0.1.0.patch"), "stale\n");

        PiSdkInstallation.copyInputs(image, install);

        for (String input : new String[] {
            "package.json",
            "pnpm-lock.yaml",
            "pnpm-workspace.yaml",
            "patches/a@1.0.0.patch",
            "patches/nested/b@2.0.0.patch"
        }) {
            assertThat(install.resolve(input)).hasSameBinaryContentAs(image.resolve(input));
        }
        assertThat(install.resolve("patches/removed@0.1.0.patch")).doesNotExist();
        assertThat(install.resolve("README.md")).doesNotExist();
        assertThat(install.resolve("node_modules")).doesNotExist();
    }

    @Test
    void shouldIdentifyTheSameInputsAlikeWhereverTheyLie() throws IOException {
        Path one = imageSdk(temp.resolve("one"));
        Path other = imageSdk(temp.resolve("other"));
        String identity = PiSdkInstallation.inputDigest(one);

        Files.writeString(one.resolve("README.md"), "edited, still not an input\n");
        Files.writeString(one.resolve("node_modules/x/index.js"), "edited, still not an input\n");

        assertThat(PiSdkInstallation.inputDigest(one))
                .isEqualTo(identity)
                .isEqualTo(PiSdkInstallation.inputDigest(other));
    }

    @Test
    void shouldIdentifyAChangedAddedRenamedOrRemovedPatchAsNewInputs() throws IOException {
        Path image = imageSdk(temp.resolve("image"));
        String original = PiSdkInstallation.inputDigest(image);
        Path patch = image.resolve("patches/nested/b@2.0.0.patch");

        Files.writeString(patch, "--- c\n+++ e\n");
        String edited = PiSdkInstallation.inputDigest(image);
        Files.writeString(patch, "--- c\n+++ d\n");
        assertThat(PiSdkInstallation.inputDigest(image)).isEqualTo(original);

        Files.writeString(image.resolve("patches/c@3.0.0.patch"), "--- f\n+++ g\n");
        String added = PiSdkInstallation.inputDigest(image);
        Files.delete(image.resolve("patches/c@3.0.0.patch"));

        Files.move(patch, image.resolve("patches/nested/b@2.0.1.patch"));
        String renamed = PiSdkInstallation.inputDigest(image);

        Files.delete(image.resolve("patches/nested/b@2.0.1.patch"));
        String removed = PiSdkInstallation.inputDigest(image);

        assertThat(List.of(original, edited, added, renamed, removed)).doesNotHaveDuplicates();
    }

    private final AtomicInteger installs = new AtomicInteger();

    /** Leaves the tree as the native install does: the copied inputs and the SDK's package entry. */
    private PiSdkInstallation.Installer installing() {
        return (image, sdk) -> {
            installs.incrementAndGet();
            PiSdkInstallation.copyInputs(image, sdk);
            Files.createDirectories(sdk.resolve(PiSdkInstallation.REQUIRED_ENTRY));
        };
    }

    @Test
    void shouldReuseOnlyTheTreeOfTheCurrentInputsWhenTheyChangeAndChangeBack() throws Exception {
        Path image = imageSdk(temp.resolve("image"));
        Path sdk = temp.resolve("sdk");
        Path patch = image.resolve("patches/nested/b@2.0.0.patch");
        String a = Files.readString(patch);
        String b = "--- c\n+++ e\n";

        PiSdkInstallation.ensureInstalled(image, sdk, installing());
        PiSdkInstallation.ensureInstalled(image, sdk, installing());
        assertThat(installs).hasValue(1);

        Files.writeString(patch, b);
        PiSdkInstallation.ensureInstalled(image, sdk, installing());
        assertThat(installs).hasValue(2);
        assertThat(sdk.resolve("patches/nested/b@2.0.0.patch")).hasContent(b);

        Files.writeString(patch, a);
        PiSdkInstallation.ensureInstalled(image, sdk, installing());
        assertThat(installs).as("the tree holds B, so A is installed again").hasValue(3);
        assertThat(sdk.resolve("patches/nested/b@2.0.0.patch")).hasContent(a);
    }

    @Test
    void shouldWithdrawTheStampBeforeInstallingAndPublishNoneWhenTheInstallFails() throws Exception {
        Path image = imageSdk(temp.resolve("image"));
        Path sdk = temp.resolve("sdk");
        PiSdkInstallation.ensureInstalled(image, sdk, installing());
        Files.writeString(image.resolve("patches/c@3.0.0.patch"), "--- f\n+++ g\n");
        var stampedWhenStarted = new AtomicBoolean(true);

        assertThatThrownBy(() -> PiSdkInstallation.ensureInstalled(image, sdk, (from, into) -> {
                    stampedWhenStarted.set(Files.exists(into.resolve(PiSdkInstallation.STAMP)));
                    PiSdkInstallation.copyInputs(from, into);
                    throw new IOException("pnpm failed");
                }))
                .hasMessage("pnpm failed");

        assertThat(stampedWhenStarted).isFalse();
        assertThat(sdk.resolve(PiSdkInstallation.STAMP)).doesNotExist();
        PiSdkInstallation.ensureInstalled(image, sdk, installing());
        assertThat(installs).hasValue(2);
    }

    @Test
    void shouldPublishNoStampWhenAnInstallLeavesNoSdkEntry() throws Exception {
        Path image = imageSdk(temp.resolve("image"));
        Path sdk = temp.resolve("sdk");

        assertThatThrownBy(() -> PiSdkInstallation.ensureInstalled(image, sdk, PiSdkInstallation::copyInputs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PiSdkInstallation.REQUIRED_ENTRY);

        assertThat(sdk.resolve(PiSdkInstallation.STAMP)).doesNotExist();
        PiSdkInstallation.ensureInstalled(image, sdk, installing());
        assertThat(installs).hasValue(1);
    }

    @Test
    // The held lock's binding is intentionally unread.
    @SuppressWarnings("try")
    void shouldTakeTheInstallLockEvenWhenTheStampMatches() throws Exception {
        Path image = imageSdk(temp.resolve("image"));
        Path sdk = temp.resolve("sdk");
        PiSdkInstallation.ensureInstalled(image, sdk, installing());

        try (var channel = FileChannel.open(sdk.resolve(".install.lock"), StandardOpenOption.WRITE);
                var held = channel.lock()) {
            // One JVM refuses an overlapping lock rather than waiting for it; the refusal shows the lock is taken.
            assertThatThrownBy(() -> PiSdkInstallation.ensureInstalled(image, sdk, installing()))
                    .isInstanceOf(OverlappingFileLockException.class);
        }
        assertThat(installs).hasValue(1);
    }
}
