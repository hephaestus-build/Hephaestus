import { GithubIcon, GitlabIcon, OutlineIcon, SlackIcon } from "@/components/icons/brand";
import { type ArtifactKindIcon, artifactKindIcon, type WorkProvider } from "@/lib/artifact-kinds";

/** The provider a piece of reviewed work lives at, once it is known to be recorded. */
export type KnownWorkProvider = NonNullable<WorkProvider>;

const PROVIDER_ICONS = {
	GITHUB: GithubIcon,
	GITLAB: GitlabIcon,
	SLACK: SlackIcon,
	OUTLINE: OutlineIcon,
} satisfies Record<KnownWorkProvider, ArtifactKindIcon>;

/**
 * The provider's mark where the caller has one — a run records its provider — falling back to the
 * kind's. Beside the work's label (`#1423`, `!88`) a kind glyph would say the same thing twice and
 * leave the reader no way to tell a GitHub request from a GitLab one.
 */
export function reviewedWorkIcon(
	kind: string | undefined,
	provider?: KnownWorkProvider,
): ArtifactKindIcon {
	return provider ? PROVIDER_ICONS[provider] : artifactKindIcon(kind);
}
