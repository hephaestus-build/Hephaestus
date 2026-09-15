import { ArrowUpIcon } from "lucide-react";

import type { PracticeTraceEntry, TracedSignal } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { AutonomyBadge } from "@/components/practice-vocabulary/AutonomyBadge";
import { Badge } from "@/components/ui/badge";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Item, ItemContent, ItemGroup, ItemTitle } from "@/components/ui/item";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { artifactKindLabel } from "@/lib/artifact-kinds";
import { PRACTICE_AUTONOMY_DESCRIPTIONS } from "@/lib/practice-autonomy";
import { hasText } from "@/lib/text";

import { deliveryLabel, occurrenceDomId, WITHHELD_REASON_LABELS } from "./trace-format";
import { TraceOutcomeBadge } from "./TraceOutcomeBadge";

export interface TracePracticeListProps {
	practices: PracticeTraceEntry[];
	/**
	 * The occurrences this trace carries. The same signal name recurs on every revision, so only the
	 * id says which occurrence an answer rests on — the list resolves each answer against these.
	 */
	signals: TracedSignal[];
	/** Named in the empty state, so a kind nothing covers says which kind it was. */
	artifactKind: string;
}

/** Every practice's answer about one piece of work, the quiet ones included. */
export function TracePracticeList({ practices, signals, artifactKind }: TracePracticeListProps) {
	const signalsById = new Map(signals.map((signal) => [signal.id, signal]));

	return (
		<section aria-labelledby="trace-practices-heading" className="min-w-0 space-y-3">
			<div className="space-y-1">
				<h2 id="trace-practices-heading" className="text-lg font-semibold">
					What each practice made of it
				</h2>
				<p className="max-w-2xl text-sm text-muted-foreground">
					Every practice this workspace runs against this kind of work is listed, including the ones
					that stayed quiet. Whether a practice was measured and whether anything was said are two
					separate things — a practice can be reviewed and still, by design, say nothing.
				</p>
			</div>
			{practices.length === 0 ? (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyTitle>No practice covers this kind of work</EmptyTitle>
						<EmptyDescription>
							This workspace runs no practice against{" "}
							{artifactKindLabel(artifactKind).toLowerCase()}, so nothing was ever going to be said
							about it.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			) : (
				<ItemGroup>
					{practices.map((entry) => (
						<TracePracticeEntry
							key={entry.practiceSlug}
							entry={entry}
							occurrence={entry.occasionedById ? signalsById.get(entry.occasionedById) : undefined}
						/>
					))}
				</ItemGroup>
			)}
		</section>
	);
}

interface TracePracticeEntryProps {
	entry: PracticeTraceEntry;
	/** The occurrence the answer rests on, when this trace carries it. */
	occurrence: TracedSignal | undefined;
}

function TracePracticeEntry({ entry, occurrence }: TracePracticeEntryProps) {
	return (
		<div role="listitem">
			<Item variant="outline" className="items-start">
				<ItemContent className="min-w-0 gap-2">
					<div className="flex w-full min-w-0 flex-wrap items-center gap-2">
						<TraceOutcomeBadge outcome={entry.outcome} />
						<ItemTitle className="min-w-0 line-clamp-none break-words">
							{entry.practiceName}
						</ItemTitle>
					</div>
					<p className="break-words text-sm text-muted-foreground">{entry.explanation}</p>
					<dl className="flex w-full min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
						<div className="flex min-w-0 items-center gap-1">
							<dt className="sr-only">Feedback delivered</dt>
							<dd className="break-words">{deliveryLabel(entry)}</dd>
						</div>
						<div className="flex min-w-0 items-center gap-1">
							<dt className="sr-only">Autonomy</dt>
							<dd>
								<Tooltip>
									<TooltipTrigger className="cursor-help">
										<AutonomyBadge autonomy={entry.autonomy} />
									</TooltipTrigger>
									<TooltipContent>{PRACTICE_AUTONOMY_DESCRIPTIONS[entry.autonomy]}</TooltipContent>
								</Tooltip>
							</dd>
						</div>
						{entry.decidedAt && (
							<div className="flex min-w-0 items-center gap-1">
								<dt className="sr-only">Decided</dt>
								<dd>
									<RelativeTime value={entry.decidedAt} className="text-xs" />
								</dd>
							</div>
						)}
						{(occurrence !== undefined || hasText(entry.occasionedBy)) && (
							<div className="flex min-w-0 items-center gap-1">
								<dt>Rests on</dt>
								<dd className="min-w-0">
									{occurrence ? (
										// The accessible name contains the visible label verbatim, so a
										// speech-control user can activate the link by the words they
										// can see (WCAG 2.2 SC 2.5.3).
										<a
											href={`#${occurrenceDomId(occurrence.id)}`}
											className="inline-flex max-w-full items-center gap-1 font-medium text-foreground underline underline-offset-4 hover:no-underline"
										>
											<span className="sr-only">Jump to: </span>
											<span className="truncate">{occurrence.displayName}</span>
											<ArrowUpIcon className="size-3 shrink-0" aria-hidden />
										</a>
									) : (
										// The id names an occurrence this trace does not carry — a skew
										// between the recorder and this endpoint.
										<span className="break-all">{entry.occasionedBy}</span>
									)}
								</dd>
							</div>
						)}
					</dl>
					{entry.withheldReasons.length > 0 && (
						<ul className="flex w-full min-w-0 flex-wrap gap-1.5" aria-label="Held back because">
							{entry.withheldReasons.map((reason) => (
								// `min-w-0`, or the badge cannot truncate: a `truncate` span is
								// `white-space: nowrap`, so its min-content width is the whole
								// sentence, and a flex item's automatic minimum size would hold
								// the list open to it and push the page wider than the viewport.
								<li key={reason} className="min-w-0">
									<Badge variant="outline" className="max-w-full">
										<span className="truncate">{WITHHELD_REASON_LABELS[reason]}</span>
									</Badge>
								</li>
							))}
						</ul>
					)}
					{entry.watches.length > 0 && (
						// Identifiers, because this endpoint sends signal names and no display
						// names; inventing labels here would disagree with the timeline above.
						<p className="w-full min-w-0 break-words text-xs text-muted-foreground">
							Starts a review on:{" "}
							{entry.watches.map((signal, index) => (
								<span key={signal}>
									{index > 0 && ", "}
									<code className="break-all">{signal}</code>
								</span>
							))}
						</p>
					)}
				</ItemContent>
			</Item>
		</div>
	);
}
