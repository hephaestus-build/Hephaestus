package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses agent image references that cannot name a build matching this server.
 *
 * <p>Applies in every runtime profile, unlike {@link AgentImagePinGuard}: a digest is a production requirement, but a
 * <em>channel tag</em> — one that moves from build to build rather than naming one — is wrong in
 * every environment. It comes in two spellings, a name and a partial version, and both move. The
 * server stages its runners into whatever this resolves to, so an unmatched image is a runtime
 * contract violation that surfaces as a failing sandbox rather than as a configuration error.
 * See ADR 0031.
 *
 * <p>The reference is parsed rather than pattern-matched on its suffix. What arrives here is the
 * result of a substitution chain — a deploy substrate's {@code IMAGE_TAG}, Compose interpolation,
 * then Spring placeholder resolution — and every link in it can produce something that is neither a
 * channel tag nor a usable reference: an empty tag from an empty {@code APP_VERSION}, or no tag at
 * all. Both reach the daemon rather than this guard unless the tag is read out and judged.
 */
@Component
@Profile("!specs & !cds-training")
public class AgentImageReferenceGuard {

    private static final Logger log = LoggerFactory.getLogger(AgentImageReferenceGuard.class);

    /** Value of {@code spring.application.version} when no {@code APP_VERSION} was supplied. */
    static final String DEVELOPMENT_VERSION = "0.0.0-development";

    /**
     * Channel tags by name. {@code latest} is what the release workflow's retag step writes and what
     * a daemon supplies for an untagged reference; {@code main} is the branch tag CI publishes on
     * every push; {@code stable} and {@code edge} are the conventional aliases an operator reaches
     * for. None is written by a build, so none can name the build this server came from.
     */
    private static final Set<String> NAMED_CHANNELS = Set.of("latest", "stable", "edge", "main");

    /**
     * A channel tag spelled in digits: a version that stops short of a patch component — {@code 0},
     * {@code 0.73}, {@code v1.2}. The release workflow retags {@code agent-pi:<major>.<minor>} onto
     * every release in that line, so such a tag moves on the next patch release exactly as
     * {@code latest} moves on the next release. A tag naming all three components, or carrying a
     * pre-release or build suffix, names one release and is left alone — as is anything not
     * version-shaped at all, such as a commit SHA or a locally built {@code dev}.
     */
    private static final Pattern VERSION_SERIES = Pattern.compile("v?\\d+(\\.\\d+)?");

    /** The tag a daemon supplies for a reference that carries none. */
    private static final String IMPLICIT_TAG = "latest";

    /** Docker's tag grammar. A reference whose tag is empty or malformed only fails at the daemon. */
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

    private static final Pattern DIGEST_PINNED = Pattern.compile("^[^@]+@sha256:[a-f0-9]{64}$");

    private static final String DOCS = "See docs/admin/release-image-lock.md.";

    private static final String FIX =
            "Pin the digest, or leave the reference unset so it follows this deployment's image tag. " + DOCS;

    private static final String CHANNEL_ADVICE =
            "A channel tag tracks whatever built most recently, so it resolves to an image built from a "
                    + "different commit than this server — a pairing no release can produce. "
                    + FIX;

    private static final String SERIES_ADVICE =
            "A `major.minor` tag is retagged onto every patch release in its line, so it resolves to an "
                    + "image built from a different commit than this server. Name the full version instead. "
                    + FIX;

    public AgentImageReferenceGuard(AgentImageProperties properties) {
        String reference = properties.reference();
        if (reference == null || reference.isBlank()) {
            throw new IllegalStateException(
                    "hephaestus.agent.image.reference is not set and could not be derived. " + DOCS);
        }
        if (reference.indexOf('@') >= 0) {
            if (!DIGEST_PINNED.matcher(reference).matches()) {
                throw new IllegalStateException(
                        "hephaestus.agent.image.reference is digest-pinned but the digest is not a sha256 of "
                                + "64 lowercase hex characters: "
                                + reference
                                + ". "
                                + DOCS);
            }
            return;
        }
        String tag = tagOf(reference);
        if (tag == null) {
            throw new IllegalStateException(
                    "hephaestus.agent.image.reference names no tag, so it resolves to `" + IMPLICIT_TAG
                            + "`: "
                            + reference
                            + ". "
                            + CHANNEL_ADVICE);
        }
        if (!TAG.matcher(tag).matches()) {
            throw new IllegalStateException("hephaestus.agent.image.reference carries no usable tag: " + reference
                    + ". The tag follows spring.application.version, so an empty one means APP_VERSION reached "
                    + "this container empty — give the deployment its image tag, or name the agent image "
                    + "explicitly. "
                    + DOCS);
        }
        if (NAMED_CHANNELS.contains(tag)) {
            throw new IllegalStateException(
                    "hephaestus.agent.image.reference must not be a channel tag: " + reference + ". " + CHANNEL_ADVICE);
        }
        if (VERSION_SERIES.matcher(tag).matches()) {
            throw new IllegalStateException(
                    "hephaestus.agent.image.reference names a version series rather than one release: " + reference
                            + ". "
                            + SERIES_ADVICE);
        }
        if (DEVELOPMENT_VERSION.equals(tag)) {
            log.warn(
                    "Agent image reference {} was derived from an unset APP_VERSION, so no such image is published. "
                            + "Set HEPHAESTUS_AGENT_IMAGE_REFERENCE to the agent image this checkout should run against. {}",
                    reference,
                    DOCS);
        }
    }

    /** The reference's tag, or {@code null} when it carries none. A registry port is not a tag. */
    private static @Nullable String tagOf(String reference) {
        int separator = reference.lastIndexOf(':');
        return separator > reference.lastIndexOf('/') ? reference.substring(separator + 1) : null;
    }
}
