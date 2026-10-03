import { cn } from "cn";
import type { ReviewedWorkRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { artifactKindNoun } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

/** The repository and the item, when a repository is recorded: `ls1intum/Hephaestus · #1423`. */
function qualifiedLabel(reviewedWork: ReviewedWorkRef): string {
	return [reviewedWork.container, reviewedWork.label].filter(hasText).join(" · ");
}

export function reviewArtifactScopeLabel(
	kind: string,
	id: number | undefined,
	reviewedWork: ReviewedWorkRef | undefined,
): string {
	if (id != null && reviewedWork) {
		return qualifiedLabel(reviewedWork);
	}
	return id == null ? `All ${artifactKindNoun(kind, 2)}` : `One ${artifactKindNoun(kind, 1)}`;
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
			<Icon className="size-3.5 shrink-0" />
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
		<InlineLink
			href={reviewedWork.url}
			external
			className={cn("relative inline-flex max-w-full min-w-0 items-center gap-1.5", className)}
		>
			<Icon className="size-3.5 shrink-0" />
			<span className="min-w-0 break-words">{qualifiedLabel(reviewedWork)}</span>
		</InlineLink>
	);
}
