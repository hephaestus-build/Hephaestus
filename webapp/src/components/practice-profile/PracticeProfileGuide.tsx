import { BookOpenIcon, ChevronDownIcon } from "lucide-react";
import { type ComponentType, useId } from "react";

import { cn } from "cn";
import { type StatusDef, statusToneClass, statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { GROUP_COLORS_NOTE } from "@/components/practice-vocabulary/group-visuals";
import { PRACTICE_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { PRACTICE_TREND_DEFS } from "@/components/practice-vocabulary/practice-trend-defs";
import { TREND_EXPLAINED } from "@/components/practice-vocabulary/practice-trend-presentation";
import { TrendWords } from "@/components/practice-vocabulary/PracticeTrendChip";
import { STANDING_BASIS_DEFS } from "@/components/practice-vocabulary/standing-basis-defs";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

export interface PracticeProfileGuideProps {
	/** Whether the explanation is open. The button that opens it stays either way, so it can come back. */
	open: boolean;
	onOpenChange: (open: boolean) => void;
}

/**
 * What a first visit needs before the page makes sense. The rules a card resolves by stay on the
 * card, which counts with the server's own number, so no number here can disagree with it.
 */
const POINTS = [
	"A practice is one way of working that your workspace cares about. Related practices form a practice group.",
	"Your standing on a practice comes from your recent reviewed work. It is not a grade. Gray means there is nothing to judge yet.",
	"A feedback card appears when reviews find the same problem in at least two pieces of your work. It resolves when several pieces of your work in a row, reviewed after it, come back clean, and the card counts how many more it needs.",
	"Feedback about one piece of work, such as a pull request, stays on that work. If something is unclear, ask Hephaestus.",
	GROUP_COLORS_NOTE,
] as const;

/**
 * Every standing as the registry words it for one practice, the two kinds of No standing yet each
 * with their own icon and reason, then both marks for a standing read from earlier reviews. Those
 * two share a label but not an icon or a sentence, so each is listed for the reader who sees it.
 */
const STANDING_ENTRIES: StatusDef[] = [
	...statusValues(PRACTICE_STANDING_DEFS).map((standing) => PRACTICE_STANDING_DEFS[standing]),
	STANDING_BASIS_DEFS.EARLIER_REVIEWS,
	STANDING_BASIS_DEFS.EARLIER_REVIEWS_CRITERIA_UNKNOWN,
];

const TREND_ENTRIES: StatusDef[] = statusValues(PRACTICE_TREND_DEFS).map(
	(direction) => PRACTICE_TREND_DEFS[direction],
);

/**
 * The page's own explanation, open on a first visit: what a practice is, what the standings and
 * trends mean, and when a feedback card appears and resolves. The label sentences are the
 * registries' own, the ones a badge shows only on hover, so each sentence has one home.
 */
export function PracticeProfileGuide({ open, onOpenChange }: PracticeProfileGuideProps) {
	return (
		<Collapsible open={open} onOpenChange={onOpenChange}>
			<CollapsibleTrigger render={<Button variant="quiet" size="sm" className="group -ml-2.5" />}>
				<BookOpenIcon aria-hidden />
				How to read this page
				<ChevronDownIcon
					aria-hidden
					className="transition-transform group-aria-expanded:rotate-180 motion-reduce:transition-none"
				/>
			</CollapsibleTrigger>
			{/* The panel itself stays unstyled: `hiddenUntilFound` keeps it in the layout while closed,
			    so a border or padding on it would draw an empty frame. */}
			<CollapsibleContent hiddenUntilFound>
				<div className="mt-2 flex max-w-4xl flex-col gap-4 rounded-xl border bg-card p-4 text-sm">
					<ul className="flex list-disc flex-col gap-1.5 pl-5">
						{POINTS.map((point) => (
							<li key={point}>{point}</li>
						))}
					</ul>
					<div className="grid gap-x-8 gap-y-4 md:grid-cols-2">
						<LabelList title="Standings" entries={STANDING_ENTRIES} Term={StandingLabel} />
						<LabelList
							title="Trends"
							description={TREND_EXPLAINED}
							entries={TREND_ENTRIES}
							Term={TrendLabel}
						/>
					</div>
				</div>
			</CollapsibleContent>
		</Collapsible>
	);
}

interface LabelListProps {
	title: string;
	description?: string;
	entries: StatusDef[];
	/** The label as the page draws it, so the reader recognizes it here. */
	Term: ComponentType<{ def: StatusDef }>;
}

/** A named group rather than a heading, so the page's outline keeps "Your feedback" as its first section. */
function LabelList({ title, description, entries, Term }: LabelListProps) {
	const titleId = useId();
	return (
		<div role="group" aria-labelledby={titleId} className="flex flex-col gap-2">
			<p id={titleId} className="font-medium">
				{title}
			</p>
			{description !== undefined && <p className="text-muted-foreground">{description}</p>}
			<dl className="flex flex-col gap-2.5">
				{entries.map((def) => (
					<div key={`${def.label}:${def.description}`} className="flex flex-col gap-1">
						<dt>
							<Term def={def} />
						</dt>
						<dd className="text-muted-foreground">{def.description}</dd>
					</div>
				))}
			</dl>
		</div>
	);
}

/** A standing and its basis mark are badges wherever the page shows them. */
function StandingLabel({ def }: { def: StatusDef }) {
	return <StatusBadge wrap def={def} />;
}

/** A trend as its chip shows it: the direction's icon and words in its tone, with no badge. */
function TrendLabel({ def }: { def: StatusDef }) {
	return (
		<span className={cn("inline-flex items-center gap-x-1", statusToneClass(def.badgeVariant))}>
			<TrendWords def={def} />
		</span>
	);
}
