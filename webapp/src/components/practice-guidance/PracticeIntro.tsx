import { ChevronDownIcon } from "lucide-react";
import { useSpinDelay } from "spin-delay";

import type { PracticeGuidance, PracticeStanding } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { LabelledBlock } from "@/components/practice-profile/practice-profile-blocks";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";

import { PracticeFigure, PracticeGuideDisclosure } from "./practice-guidance-blocks";

/** The practice's picture and "Read more" guide as they load; either may be absent once in. */
export type PracticeGuidanceState = PanelState<Pick<PracticeGuidance, "visual" | "guide">>;

export interface PracticeIntroProps {
	/**
	 * The catalog's words, from the standing the level already holds: why the practice matters
	 * leads, and what good looks like captions the picture.
	 */
	practice: Pick<PracticeStanding, "whyItMatters" | "whatGoodLooksLike">;
	/** The picture and the guide, which arrive after the words. */
	guidance: PracticeGuidanceState;
	/** Whether the reader hid the introduction. Controlled: the route remembers it per practice. */
	hidden: boolean;
	onHiddenChange: (hidden: boolean) => void;
}

/**
 * The top of a practice's level: why it matters, the visual captioned with what good looks like,
 * and the guide behind "Read more". The words come with the standing and show at once. The visual
 * and the guide load on their own, so only their area waits or fails. The toggle comes before what
 * it hides, so hiding leaves it in place.
 */
export function PracticeIntro({ practice, guidance, hidden, onHiddenChange }: PracticeIntroProps) {
	// `ssr: false`, or a level that mounts with the request in flight shows the skeleton at once.
	const showSkeleton = useSpinDelay(guidance.status === "loading", {
		delay: 1000,
		minDuration: 500,
		ssr: false,
	});
	// Undefined until the answer is in and the skeleton has had its minimum time.
	const settled = guidance.status === "loading" || showSkeleton ? undefined : guidance;
	const visual = settled?.status === "ready" ? settled.visual : undefined;
	const guide = settled?.status === "ready" ? settled.guide : undefined;
	const { whyItMatters: lead, whatGoodLooksLike: caption } = practice;

	if (
		!hasText(lead) &&
		!hasText(caption) &&
		!showSkeleton &&
		visual === undefined &&
		guide === undefined &&
		settled?.status !== "error"
	) {
		return null;
	}

	return (
		<Collapsible
			open={!hidden}
			onOpenChange={(open) => onHiddenChange(!open)}
			render={<section aria-label="Introduction" />}
		>
			<div className="flex justify-end">
				<CollapsibleTrigger render={<Button variant="quiet" size="sm" className="group" />}>
					{hidden ? "Show introduction" : "Hide introduction"}
					<ChevronDownIcon
						data-icon="inline-end"
						className="transition-transform group-aria-expanded:rotate-180"
						aria-hidden
					/>
				</CollapsibleTrigger>
			</div>
			<CollapsibleContent className="mt-2 flex flex-col gap-4">
				{hasText(lead) && <p className="max-w-2xl text-base text-pretty">{lead}</p>}
				{showSkeleton && (
					<div className="flex flex-col gap-2" aria-busy="true">
						<span className="sr-only">Loading the picture for this practice…</span>
						<Skeleton className="aspect-2/1 max-h-80 w-full rounded-xl" />
						{hasText(caption) && (
							<p className="max-w-2xl text-sm text-muted-foreground">{caption}</p>
						)}
						<Skeleton className="h-7 w-24" />
					</div>
				)}
				{visual && <PracticeFigure visual={visual} caption={caption} />}
				{settled !== undefined && visual === undefined && hasText(caption) && (
					<LabelledBlock label="What good looks like" className="flex flex-col gap-1.5">
						<p className="max-w-2xl text-sm">{caption}</p>
					</LabelledBlock>
				)}
				{settled?.status === "error" && (
					<p className="max-w-2xl text-sm text-muted-foreground">
						We could not load the picture and guide for this practice.{" "}
						<Button variant="link" size="inline" onClick={settled.onRetry}>
							Try again
						</Button>
					</p>
				)}
				{guide && <PracticeGuideDisclosure guide={guide} />}
			</CollapsibleContent>
		</Collapsible>
	);
}
