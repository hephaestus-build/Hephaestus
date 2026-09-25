package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A piece of reviewed work as every surface names it: enough to print a link. */
@Schema(description = "A piece of reviewed work as every surface names it: enough to print a link")
public record ReviewedWorkRefDTO(
        @NonNull @Schema(description = "Identifier of the work within its kind")
        String id,

        @NonNull @Schema(description = "Artifact kind id") ArtifactKind kind,

        @Nullable
        @Schema(
                description = "The provider the work lives at, which decides its noun: a GitLab scm.pull_request is a"
                        + " merge request; absent when the run that named the work is gone")
        IntegrationKind provider,

        @NonNull
        @Schema(description = "Short label the page prints: \"#22\", \"!425\", \"#backend-review\" or a document title")
        String label,

        @Nullable
        @Schema(
                description =
                        "The work's own title: a pull request's, an issue's or a document's; a conversation thread has none")
        String title,

        @Nullable @Schema(description = "The work's page at its provider, when the provider exposes one")
        String url,

        @Nullable @Schema(description = "Repository the work belongs to, for pull requests and issues")
        String repositoryName) {}
