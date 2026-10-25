import { RepeatIcon } from "lucide-react";

import { cn } from "cn";

import { cleanProgress } from "@/components/practice-vocabulary/practice-feedback-timeline";

import type { PracticePattern } from "./practice-pattern";

export interface PracticePatternLineProps {
	pattern: PracticePattern;
	className?: string;
}

/** A practice has a pattern exactly when it has an open card, so the label says what the reader can act on. */
const PATTERN = "Has open feedback";

/** The card's own meter, small: one segment per clean piece of work the card waits for. */
function CleanSegments({ pattern }: { pattern: PracticePattern }) {
	return Array.from({ length: pattern.cleanNeeded }, (_, index) => (
		<span
			key={index}
			className={cn(
				"h-1.5 w-3 rounded-full",
				index < pattern.cleanCount ? "bg-success" : "bg-border",
			)}
		/>
	));
}

/**
 * The pattern of a practice, in the words of its open card: the clean work since, drawn as the
 * card's own meter. A plain label beside
 * the practice's name in its group's table, never a control: the row's own link opens the practice.
 */
export function PracticePatternLine({ pattern, className }: PracticePatternLineProps) {
	return (
		<span
			className={cn(
				// One line where it fits; on a phone the progress words wrap to a second line inside the
				// label rather than run past the table's edge.
				"inline-flex min-h-6 w-fit max-w-full flex-wrap items-center gap-x-1.5 gap-y-0.5 rounded-xl border border-border bg-card px-2.5 py-0.5 text-xs font-medium text-foreground",
				className,
			)}
		>
			<span className="inline-flex items-center gap-1.5 whitespace-nowrap">
				<RepeatIcon aria-hidden className="size-3.5 shrink-0 text-muted-foreground" />
				<span>{PATTERN}</span>
				<span aria-hidden className="inline-flex gap-0.5">
					<CleanSegments pattern={pattern} />
				</span>
			</span>
			{/* The segments are drawn for the eye; the count beside them is the words for everyone. */}
			<span className="min-w-0 font-normal text-muted-foreground tabular-nums">
				<span className="sr-only">, </span>
				{cleanProgress(pattern)}
			</span>
		</span>
	);
}
