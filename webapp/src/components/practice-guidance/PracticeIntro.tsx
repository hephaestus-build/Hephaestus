import { cn } from "cn";

import type { PracticeGuidanceDto, PracticeStanding } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { LabelledBlock, LevelLead } from "@/components/practice-profile/practice-profile-blocks";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";
import { useSpinDelay } from "@/lib/use-spin-delay";

import { PracticeFigure } from "./PracticeFigure";
import { PRACTICE_VISUAL_WIDTH } from "./PracticeVisual";

export type PracticeGuidanceState = PanelState<PracticeGuidanceDto>;

export interface PracticeIntroProps {
	/**
	 * The catalog's words, from the standing the level already holds: why the practice matters
	 * leads, and what good looks like captions the picture.
	 */
	practice: Pick<PracticeStanding, "whyItMatters" | "whatGoodLooksLike">;
	/** The picture, which arrives after the words. The guide is the level's Guide tab. */
	guidance: PracticeGuidanceState;
	/** The level of the "What good looks like" label: `h4` inside a record sheet's section. */
	labelAs?: "h2" | "h4";
}

/**
 * The top of a practice's level: why it matters, then the visual with what good looks like as its
 * caption. The words come with the standing and show at once; only the picture's area waits or
 * fails. Everything shares one text column and the body size, `text-sm`, as the guide does: the lead
 * stands out by its place and its ink, not by a larger size.
 */
export function PracticeIntro({ practice, guidance, labelAs = "h2" }: PracticeIntroProps) {
	// `ssr: false`, or a level that mounts with the request in flight shows the skeleton at once.
	const showSkeleton = useSpinDelay(guidance.status === "loading", {
		delay: 1000,
		minDuration: 500,
		ssr: false,
	});
	// Undefined until the answer is in and the skeleton has had its minimum time.
	const settled = guidance.status === "loading" || showSkeleton ? undefined : guidance;
	const visual = settled?.status === "ready" ? (settled.visual ?? undefined) : undefined;
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
			{hasText(lead) && <LevelLead>{lead}</LevelLead>}
			{showSkeleton && (
				<div className="flex flex-col gap-2 text-sm" aria-busy="true">
					<span className="sr-only">Loading the picture for this practice…</span>
					<Skeleton className={cn("aspect-2/1 w-full rounded-md", PRACTICE_VISUAL_WIDTH)} />
					{hasText(caption) && <p className="text-muted-foreground">{caption}</p>}
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
				<LabelledBlock label="What good looks like" as={labelAs} className="flex flex-col gap-1.5">
					<p className="text-sm text-pretty">{caption}</p>
				</LabelledBlock>
			)}
		</section>
	);
}
