package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.release.ReleaseStatusDTO.RunningReleaseDTO;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The identity this process reports, on every runtime role, under {@code release} in
 * {@code /actuator/info} and in the instance-admin release API. Every value is what the deployment
 * handed the container from its verified lock env; the JAR carries no version of its own because
 * release promotion tags an already-built image (see {@code docs/contributor/release-management.mdx}).
 */
@Component
public class RunningRelease implements InfoContributor {
    /** A release tag without its {@code v}: the {@code IMAGE_TAG} a release lock renders. */
    static final Pattern RELEASE_VERSION =
            Pattern.compile("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})");

    private static final Pattern COMMIT_VERSION = Pattern.compile("[a-f0-9]{40}");

    private final RunningReleaseDTO identity;

    public RunningRelease(
            @Value("${spring.application.version}") String version,
            ReleaseProperties properties,
            Environment environment) {
        ReleaseChannel channel = RELEASE_VERSION.matcher(version).matches()
                ? ReleaseChannel.RELEASE
                : COMMIT_VERSION.matcher(version).matches() ? ReleaseChannel.COMMIT : ReleaseChannel.DEVELOPMENT;
        identity = new RunningReleaseDTO(
                version,
                channel,
                properties.commit().isEmpty() ? null : properties.commit(),
                properties.image().isEmpty() ? null : properties.image(),
                RuntimeRole.enabled(environment));
    }

    public RunningReleaseDTO get() {
        return identity;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("release", identity);
    }
}
