import { ExternalLinkIcon } from "lucide-react";

import { cn } from "cn";
import type { ReviewedWorkRef } from "@/api/types.gen";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import {
	ARTIFACT_KIND,
	ARTIFACT_KIND_VALUES,
	artifactKindLabel,
	artifactKindPluralLabel,
	isKnownArtifactKind,
	type KnownArtifactKind,
} from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

/**
 * URL-facing spelling of a kind: the wire id carries a dot, which reads badly in a path segment, so
 * the routes keep their own short slug and this map is where the two meet.
 */
const ARTIFACT_KIND_SLUGS = {
	[ARTIFACT_KIND.pullRequest]: "pull-request",
	[ARTIFACT_KIND.issue]: "issue",
	[ARTIFACT_KIND.conversationThread]: "conversation",
	[ARTIFACT_KIND.document]: "document",
} as const satisfies Record<KnownArtifactKind, string>;

export type ReviewArtifactTypeSlug = (typeof ARTIFACT_KIND_SLUGS)[keyof typeof ARTIFACT_KIND_SLUGS];

export function reviewArtifactTypeSlug(kind: string): ReviewArtifactTypeSlug | undefined {
	return isKnownArtifactKind(kind) ? ARTIFACT_KIND_SLUGS[kind] : undefined;
}

export function reviewArtifactTypeFromSlug(slug: string): KnownArtifactKind | undefined {
	return ARTIFACT_KIND_VALUES.find((kind) => ARTIFACT_KIND_SLUGS[kind] === slug);
}

/** The repository and the item, when a repository is recorded: `ls1intum/Hephaestus · #1423`. */
function qualifiedLabel(reviewedWork: ReviewedWorkRef): string {
	return [reviewedWork.repositoryName, reviewedWork.label].filter(hasText).join(" · ");
}

export function reviewArtifactScopeLabel(
	kind: string,
	id: number | undefined,
	reviewedWork: ReviewedWorkRef | undefined,
): string {
	if (id != null && reviewedWork) {
		return qualifiedLabel(reviewedWork);
	}
	// Lower-cased from the registry rather than spelled out again here: a fifth artifact kind is one
	// edit to `lib/artifact-kinds.ts` and its ten call sites, and a local copy is the one that would
	// be missed. Mid-sentence is the only reason the case differs at all.
	const scope = (
		id == null ? artifactKindPluralLabel(kind) : artifactKindLabel(kind)
	).toLowerCase();
	return `${id == null ? "All" : "One"} ${scope}`;
}

export interface ReviewArtifactProps {
	/**
	 * The work as the wire names it, provider included: the mark is the ref's own, so no caller
	 * passes a provider beside it and none can pass one the ref disagrees with.
	 */
	reviewedWork: ReviewedWorkRef | undefined;
	className?: string;
}

/**
 * Never the work's title: it is long, and every surface that shows one already has somewhere better
 * to put it — the run row uses it as the row's own name, the run page as the heading.
 */
export function ReviewArtifactLabel({ reviewedWork, className }: ReviewArtifactProps) {
	if (!reviewedWork) {
		return <span className={cn("text-muted-foreground", className)}>No reviewed work</span>;
	}
	const Icon = reviewedWorkIcon(reviewedWork.kind, reviewedWork.provider);
	return (
		<span className={cn("inline-flex max-w-full min-w-0 items-center gap-1.5", className)}>
			<Icon className="size-3.5 shrink-0" aria-hidden />
			<span className="min-w-0 break-words">{qualifiedLabel(reviewedWork)}</span>
		</span>
	);
}

/**
 * The anchor contains the label and nothing else, so a hover affordance can never reach text that is
 * not the link's name. A caller that wants the work's title renders it outside.
 */
export function ReviewArtifactLink({ reviewedWork, className }: ReviewArtifactProps) {
	if (!hasText(reviewedWork?.url)) {
		return <ReviewArtifactLabel reviewedWork={reviewedWork} className={className} />;
	}
	const Icon = reviewedWorkIcon(reviewedWork.kind, reviewedWork.provider);
	return (
		<a
			href={reviewedWork.url}
			target="_blank"
			rel="noopener noreferrer"
			className={cn(
				"group relative inline-flex max-w-full min-w-0 items-center gap-1.5 rounded-sm",
				className,
			)}
		>
			<Icon className="size-3.5 shrink-0" aria-hidden />
			{/* `group-hover`, not `hover`, so the affordance answers the whole link — and it is on the
			    label alone, so it can never reach a title rendered beside it. */}
			<span className="min-w-0 break-words group-hover:underline">
				{qualifiedLabel(reviewedWork)}
			</span>
			<ExternalLinkIcon className="size-3 shrink-0 text-muted-foreground" aria-hidden />
			<span className="sr-only"> (opens in a new tab)</span>
		</a>
	);
}
