import type { ComponentType } from "react";

import { GithubIcon, GitlabIcon, OutlineIcon, SlackIcon } from "@/components/icons/brand";
import { artifactKindIcon, type WorkProvider } from "@/lib/artifact-kinds";

/** Wide enough for the brand marks and for the kind registry's lucide and octicon glyphs. */
export type WorkGlyph = ComponentType<{ className?: string; "aria-hidden"?: boolean }>;

/** The provider a piece of reviewed work lives at, once it is known to be recorded. */
export type KnownWorkProvider = NonNullable<WorkProvider>;

const PROVIDER_ICONS = {
	GITHUB: GithubIcon,
	GITLAB: GitlabIcon,
	SLACK: SlackIcon,
	OUTLINE: OutlineIcon,
} satisfies Record<KnownWorkProvider, WorkGlyph>;

/**
 * The provider's mark where the caller has one — a run records its provider — falling back to the
 * kind's. Beside the work's label (`#1423`, `!88`) a kind glyph would say the same thing twice and
 * leave the reader no way to tell a GitHub request from a GitLab one.
 *
 * Lives here rather than beside either caller: the admin console's review pages and the developer's
 * practice profile both name reviewed work, and a mark that answered on one surface only is how the
 * two drift apart.
 */
export function reviewedWorkIcon(
	kind: string | undefined,
	provider?: KnownWorkProvider,
): WorkGlyph {
	return provider ? PROVIDER_ICONS[provider] : artifactKindIcon(kind);
}
