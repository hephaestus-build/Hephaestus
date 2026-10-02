package de.tum.cit.aet.hephaestus.testconfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The agent image's Pi SDK, installed for the live tests from the image's own manifest, lockfile and
 * workspace file, so a live runner runs the dependency tree the image ships rather than a second pin of it.
 */
public final class PiSdkInstallation {

    /** Build output under the test JVM's working directory ({@code server/application}); gitignored. */
    public static final Path SDK_DIR = Path.of("target", "pi-sdk").toAbsolutePath();

    private static final Path IMAGE_SDK =
            Path.of("..", "..", "docker", "agents", "pi").toAbsolutePath().normalize();

    // pnpm resolves against the nearest pnpm-workspace.yaml at or above the install directory; without the
    // image's own one here, it anchors on the repository root and installs somewhere else.
    private static final List<String> MANIFEST = List.of("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml");

    private PiSdkInstallation() {}

    /**
     * Installs the SDK once per lockfile content; a marker skips repeat installs and a sibling lock serializes
     * concurrent JVMs.
     *
     * @return the {@code node_modules} directory a runner's workspace links to
     */
    // Closing the lock is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    public static Path ensureInstalled() throws IOException, InterruptedException {
        Files.createDirectories(SDK_DIR);
        Path marker = SDK_DIR.resolve(".installed-" + manifestDigest());
        if (!Files.exists(marker)) {
            try (var channel = FileChannel.open(
                            SDK_DIR.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                if (!Files.exists(marker)) {
                    install();
                    Files.writeString(marker, "ok\n");
                }
            }
        }
        Path nodeModules = SDK_DIR.resolve("node_modules");
        if (!Files.isDirectory(nodeModules.resolve("@earendil-works/pi-coding-agent"))) {
            throw new IllegalStateException("Pi SDK install marker present but @earendil-works/pi-coding-agent is "
                    + "missing under " + SDK_DIR + " — delete target/pi-sdk and re-run.");
        }
        return nodeModules;
    }

    private static void install() throws IOException, InterruptedException {
        for (String name : MANIFEST) {
            Files.copy(IMAGE_SDK.resolve(name), SDK_DIR.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        Process process = new ProcessBuilder(
                        "pnpm", "install", "--prod", "--frozen-lockfile", "--ignore-scripts", "--reporter=silent")
                .directory(SDK_DIR.toFile())
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

    private static String manifestDigest() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String name : MANIFEST) {
                digest.update(Files.readAllBytes(IMAGE_SDK.resolve(name)));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
