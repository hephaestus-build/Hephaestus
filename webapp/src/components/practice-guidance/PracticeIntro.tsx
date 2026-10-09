import { useSpinDelay } from "spin-delay";

import type { PracticeGuidance, PracticeStanding } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { LabelledBlock } from "@/components/practice-profile/practice-profile-blocks";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";

import { PracticeFigure } from "./PracticeFigure";

/** The practice's picture and guide as they load; either may be absent once in. */
export type PracticeGuidanceState = PanelState<Pick<PracticeGuidance, "visual" | "guide">>;

export interface PracticeIntroProps {
	/**
	 * The catalog's words, from the standing the level already holds: why the practice matters
	 * leads, and what good looks like captions the picture.
	 */
	practice: Pick<PracticeStanding, "whyItMatters" | "whatGoodLooksLike">;
	/** The picture, which arrives after the words. The guide is the level's Guide tab. */
	guidance: PracticeGuidanceState;
}

/**
 * The top of a practice's level: why it matters, then the visual with what good looks like as its
 * caption. The words come with the standing and show at once; only the picture's area waits or
 * fails. Everything shares one text column, so the picture lines up with the words around it.
 */
export function PracticeIntro({ practice, guidance }: PracticeIntroProps) {
	// `ssr: false`, or a level that mounts with the request in flight shows the skeleton at once.
	const showSkeleton = useSpinDelay(guidance.status === "loading", {
		delay: 1000,
		minDuration: 500,
		ssr: false,
	});
	// Undefined until the answer is in and the skeleton has had its minimum time.
	const settled = guidance.status === "loading" || showSkeleton ? undefined : guidance;
	const visual = settled?.status === "ready" ? settled.visual : undefined;
	const { whyItMatters: lead, whatGoodLooksLike: caption } = practice;

	if (
		!hasText(lead) &&
		!hasText(caption) &&
		!showSkeleton &&
		visual === undefined &&
		settled?.status !== "error"
	) {
		return null;
	}

	return (
		<section aria-label="Introduction" className="flex max-w-2xl flex-col gap-4">
			{hasText(lead) && <p className="text-base text-pretty">{lead}</p>}
			{showSkeleton && (
				<div className="flex flex-col gap-2" aria-busy="true">
					<span className="sr-only">Loading the picture for this practice…</span>
					<Skeleton className="aspect-2/1 w-full rounded-md" />
					{hasText(caption) && <p className="text-sm text-muted-foreground">{caption}</p>}
				</div>
			)}
			{visual && <PracticeFigure visual={visual} caption={caption} />}
			{settled?.status === "error" && (
				<p className="text-sm text-muted-foreground">
					We could not load the picture and guide for this practice.{" "}
					<Button variant="link" size="inline" onClick={settled.onRetry}>
						Try again
					</Button>
				</p>
			)}
			{settled !== undefined && visual === undefined && hasText(caption) && (
				<LabelledBlock label="What good looks like" className="flex flex-col gap-1.5">
					<p className="text-sm text-pretty">{caption}</p>
				</LabelledBlock>
			)}
		</section>
	);
}
