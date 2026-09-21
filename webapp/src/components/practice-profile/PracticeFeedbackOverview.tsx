import { ChevronDownIcon } from "lucide-react";
import { useState } from "react";

import type { PracticeGroup } from "@/api/types.gen";
import { spell } from "@/components/common/feedback-text";
import { FeedbackText } from "@/components/common/FeedbackText";
import { Section } from "@/components/core/Section";
import {
	type FeedbackBlock,
	HephFeedbackCard,
} from "@/components/practice-vocabulary/HephFeedbackCard";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

import type { ComposedOverview } from "./compose-overview";

export interface PracticeFeedbackOverviewProps {
	/** The composed overview: what held, what changed and its rest, and the reviewed work. */
	overview: ComposedOverview;
	onOpenPractice?: (practiceSlug: string) => void;
	/** The workspace's groups: where a group the paragraph names reads its icon and colour. */
	groups?: PracticeGroup[];
	onOpenGroup?: (groupSlug: string) => void;
	isLoading?: boolean;
	className?: string;
}

/**
 * The page's overview: the section heading, then Heph's card with the "What changed" block and
 * its unfolded rest.
 */
export function PracticeFeedbackOverview({
	overview,
	onOpenPractice,
	groups,
	onOpenGroup,
	isLoading = false,
	className,
}: PracticeFeedbackOverviewProps) {
	const { holdingUp, holdingUpNote, reviewedWork, changed } = overview;
	const changedBlock: FeedbackBlock = {
		label: "What changed",
		content: (
			<ChangedContent
				overview={overview}
				onOpenPractice={onOpenPractice}
				groups={groups}
				onOpenGroup={onOpenGroup}
			/>
		),
	};
	return (
		<Section
			size="lg"
			title="Practice development feedback"
			description="What holds and what moved across your work since the latest run. A piece of feedback resolves once your work comes back clean."
			className={className}
		>
			<HephFeedbackCard
				holdingUp={holdingUp}
				holdingUpNote={holdingUpNote}
				reviewedWork={reviewedWork}
				onOpenPractice={onOpenPractice}
				blocks={changed.length > 0 ? [changedBlock] : []}
				isLoading={isLoading}
			/>
		</Section>
	);
}

/**
 * The paragraph and, under it, what it only counted: "See the other seven" unfolds one paragraph
 * per kind in place. The disclosure is this block's own state, not a place in the URL.
 */
function ChangedContent({
	overview: { changed, rest, restCount },
	onOpenPractice,
	groups,
	onOpenGroup,
}: Pick<PracticeFeedbackOverviewProps, "overview" | "onOpenPractice" | "groups" | "onOpenGroup">) {
	// The trigger's words follow the state, so the disclosure is controlled rather than left to the
	// primitive.
	const [open, setOpen] = useState(false);
	return (
		<>
			<FeedbackText
				as="p"
				segments={changed}
				onOpenPractice={onOpenPractice}
				groups={groups}
				onOpenGroup={onOpenGroup}
				className="max-w-3xl text-sm"
			/>
			{rest.length > 0 && (
				<Collapsible open={open} onOpenChange={setOpen} className="flex flex-col gap-2 pt-1">
					<CollapsibleTrigger
						render={<Button variant="link" size="inline" className="group w-fit text-sm" />}
					>
						{open ? "Show less" : `See the other ${spell(restCount)}`}
						<ChevronDownIcon
							className="size-3.5 transition-transform group-aria-expanded:rotate-180"
							aria-hidden
						/>
					</CollapsibleTrigger>
					<CollapsibleContent className="flex flex-col gap-2">
						{rest.map((paragraph) => (
							<p key={paragraph.title} className="max-w-3xl text-sm">
								<span className="font-semibold">{paragraph.title}.</span>{" "}
								<FeedbackText
									segments={paragraph.segments}
									onOpenPractice={onOpenPractice}
									groups={groups}
									onOpenGroup={onOpenGroup}
								/>
							</p>
						))}
					</CollapsibleContent>
				</Collapsible>
			)}
		</>
	);
}
