package de.tum.cit.aet.hephaestus.testconfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The agent image's Pi SDK, installed for the live tests from the image's own manifest, lockfile, workspace file
 * and patches, so a live runner runs the dependency tree the image ships rather than a second pin of it.
 */
public final class PiSdkInstallation {

    /** Build output under the test JVM's working directory ({@code server/application}); gitignored. */
    public static final Path SDK_DIR = Path.of("target", "pi-sdk").toAbsolutePath();

    private static final Path IMAGE_SDK =
            Path.of("..", "..", "docker", "agents", "pi").toAbsolutePath().normalize();

    // pnpm resolves against the nearest pnpm-workspace.yaml at or above the install directory; without the
    // image's own one here, it anchors on the repository root and installs somewhere else.
    private static final List<String> MANIFEST = List.of("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml");

    private static final String PATCHES = "patches";

    /** Names the inputs the tree under the SDK directory was last installed from; absent while it is not. */
    static final String STAMP = ".installed";

    static final String REQUIRED_ENTRY = "node_modules/@earendil-works/pi-coding-agent";

    /** Puts the inputs into the SDK directory and installs from them. */
    @FunctionalInterface
    interface Installer {
        void install(Path imageSdk, Path sdkDir) throws IOException, InterruptedException;
    }

    private PiSdkInstallation() {}

    /**
     * Installs the SDK unless the directory already holds the tree of the current inputs. A lock beside it serializes
     * installers in other JVMs; it guards the install, not the later use of the tree.
     *
     * @return the {@code node_modules} directory a runner's workspace links to
     */
    public static Path ensureInstalled() throws IOException, InterruptedException {
        return ensureInstalled(IMAGE_SDK, SDK_DIR, PiSdkInstallation::install);
    }

    // Closing the lock is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    static Path ensureInstalled(Path imageSdk, Path sdkDir, Installer installer)
            throws IOException, InterruptedException {
        Files.createDirectories(sdkDir);
        try (var channel = FileChannel.open(
                        sdkDir.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            String digest = inputDigest(imageSdk);
            Path stamp = sdkDir.resolve(STAMP);
            Path required = sdkDir.resolve(REQUIRED_ENTRY);
            boolean current = Files.isRegularFile(stamp)
                    && Files.readString(stamp).strip().equals(digest)
                    && Files.isDirectory(required);
            if (!current) {
                // From here until the stamp is written again, the tree matches no inputs.
                Files.deleteIfExists(stamp);
                installer.install(imageSdk, sdkDir);
                if (!Files.isDirectory(required)) {
                    throw new IllegalStateException("Pi SDK install finished without " + REQUIRED_ENTRY);
                }
                Files.writeString(stamp, digest + "\n");
            }
        }
        return sdkDir.resolve("node_modules");
    }

    private static void install(Path imageSdk, Path sdkDir) throws IOException, InterruptedException {
        copyInputs(imageSdk, sdkDir);
        Process process = new ProcessBuilder(
                        "pnpm", "install", "--prod", "--frozen-lockfile", "--ignore-scripts", "--reporter=silent")
                .directory(sdkDir.toFile())
                .inheritIO()
                .start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("pnpm install for Pi SDK timed out after 180s");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("pnpm install for Pi SDK failed; see stderr above");
        }
    }

    private static List<Path> inputs(Path imageSdk) throws IOException {
        List<Path> inputs = new ArrayList<>(MANIFEST.stream().map(Path::of).toList());
        Path patches = imageSdk.resolve(PATCHES);
        if (Files.isDirectory(patches)) {
            try (Stream<Path> files = Files.walk(patches)) {
                files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        .map(imageSdk::relativize)
                        .forEach(inputs::add);
            }
        }
        inputs.sort(Comparator.comparing(Path::toString));
        return inputs;
    }

    /** Copies the inputs under the same relative names; patches no longer in the image do not survive. */
    static void copyInputs(Path imageSdk, Path target) throws IOException {
        Path patches = target.resolve(PATCHES);
        if (Files.exists(patches)) {
            try (Stream<Path> stale = Files.walk(patches)) {
                for (Path path : stale.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        for (Path input : inputs(imageSdk)) {
            Path copy = target.resolve(input);
            Files.createDirectories(copy.getParent());
            Files.copy(imageSdk.resolve(input), copy, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String inputDigest(Path imageSdk) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path input : inputs(imageSdk)) {
                byte[] content = Files.readAllBytes(imageSdk.resolve(input));
                digest.update(input.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(
                        ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
                digest.update(content);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
