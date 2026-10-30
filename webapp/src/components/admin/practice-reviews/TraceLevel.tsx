import { type ReactNode, useState } from "react";

import type { ItemEvent, ItemRef, ReviewDetail, ReviewLineage } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import {
	ITEM_EVENT_FOUND_BY,
	ITEM_EVENT_STATE_DEFS,
} from "@/components/practice-vocabulary/item-event-defs";
import type { ReviewOrigin } from "@/components/practice-vocabulary/review-origin-defs";
import { DrawerBody } from "@/components/ui/drawer";
import { hasText } from "@/lib/text";

import { LevelBodySkeleton } from "./LevelBodySkeleton";
import { TRACE_STAGE_LABELS } from "./lineage-story";
import { Step } from "./LineageStep";
import type { RecordRef } from "./review-layers";
import type { TraceNode } from "./review-trace";
import { ReviewItemLabel } from "./ReviewItem";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewLineageGraph } from "./ReviewLineageGraph";
import type { TraceDisplay } from "./trace-display";
import type { TraceFilter } from "./trace-filter";
import { TRACE_END_TONES, unreviewedVerdict } from "./trace-verdict";
import { TraceLegend } from "./TraceLegend";

export type TraceChainState =
	| PanelState<{ lineage: ReviewLineage }>
	| {
			status: "unreviewed";
			item: ItemRef;
			event: ItemEvent;
			eventLabel: string;
	  }
	| { status: "none" };

export interface TraceLevelProps {
	nested?: boolean;
	path: LevelPath;
	/** What the trace stands on, in words: "Review of #1423 · 7 Oct, 14:02". */
	title: ReactNode;
	/** A line under the title, where the trace stands on something other than the row it came from. */
	description?: ReactNode;
	chain: TraceChainState;
	here: TraceNode | undefined;
	/** Opens a step's record over the trace, as the one level above it. */
	onOpen: (record: RecordRef) => void;
	/** What the trace shows on top of its steps: times, system info, siblings, connections. */
	display: TraceDisplay;
	onDisplayChange: (display: TraceDisplay) => void;
	filter: TraceFilter;
	onFilterChange: (filter: TraceFilter) => void;
	review?: ReviewDetail;
	origin?: ReviewOrigin;
	/** Where the review stands among the reviews of its work, from its row. */
	position?: { index: number; total: number };
	workRecord?: RecordRef;
	/**
	 * Whether Practice reviews are on for the workspace now, so the trace of an item without a
	 * review never says they are off while they are on.
	 */
	practicesEnabled?: boolean;
}

/**
 * The trace of one record, as a wide sheet over the tables: where it came from, its siblings, where
 * it stands and what became of it. A step opens its record's sheet over the
 * trace; that sheet links nowhere, so Escape closes it back to the trace, then the trace.
 */
export function TraceLevel({
	nested,
	path,
	title,
	description,
	chain,
	workRecord,
	...story
}: TraceLevelProps) {
	return (
		<>
			<ReviewLevelHeader
				nested={nested}
				path={path}
				kind="trace"
				loading={chain.status === "loading"}
				title={title}
				description={description}
			/>
			<DrawerBody className="pt-2">
				<TraceBody chain={chain} workRecord={workRecord} {...story} />
			</DrawerBody>
		</>
	);
}

function TraceBody({
	chain,
	workRecord,
	practicesEnabled,
	onOpen,
	...story
}: Omit<TraceLevelProps, "nested" | "path" | "title" | "description">) {
	switch (chain.status) {
		case "loading": {
			return <LevelBodySkeleton />;
		}
		case "error": {
			return (
				<QueryErrorAlert
					error={chain.error}
					title="We could not load this trace"
					onRetry={chain.onRetry}
				/>
			);
		}
		case "none": {
			return (
				<p className="text-sm text-muted-foreground">
					Nothing was recorded about this record, so there is no trace to show.
				</p>
			);
		}
		case "unreviewed": {
			return (
				<UnreviewedTrace
					item={chain.item}
					event={chain.event}
					eventLabel={chain.eventLabel}
					practicesEnabled={practicesEnabled}
					onOpen={workRecord === undefined ? undefined : () => onOpen(workRecord)}
				/>
			);
		}
		case "ready": {
			return <ReviewLineageGraph lineage={chain.lineage} onOpen={onOpen} {...story} />;
		}
	}
}

function UnreviewedTrace({
	item,
	event,
	eventLabel,
	practicesEnabled,
	onOpen,
}: {
	item: ItemRef;
	event: ItemEvent;
	eventLabel: string;
	practicesEnabled: boolean | undefined;
	/** Opens the item's sheet; absent where the trace has no item to open, so the event is text. */
	onOpen?: () => void;
}) {
	const [legendOpen, setLegendOpen] = useState(false);
	const verdict = unreviewedVerdict(event, practicesEnabled);
	const eventLine = (
		<>
			<span className="block font-medium">{eventLabel}</span>
			<span className="block text-muted-foreground">
				{ITEM_EVENT_FOUND_BY[event.foundBy].label} · {ITEM_EVENT_STATE_DEFS[event.state].label}
			</span>
		</>
	);
	return (
		<div role="group" aria-label="How this came about" className="min-w-0 space-y-3">
			<p data-trace-verdict className="text-sm font-medium text-balance">
				{verdict.sentence}
			</p>
			<div className="flex justify-end">
				<TraceLegend open={legendOpen} onOpenChange={setLegendOpen} />
			</div>
			<ol aria-label="Steps, oldest first" className="text-xs">
				<li className="grid grid-cols-[0.6875rem_minmax(0,1fr)] gap-x-2.5 pb-1.5">
					<span aria-hidden />
					<span className="px-1.5 text-2xs font-semibold tracking-wide text-muted-foreground uppercase">
						{TRACE_STAGE_LABELS.start}
					</span>
				</li>
				<Step dot={{ tone: "outline" }} data-step="work">
					<span className="block px-1.5 py-1">
						<ReviewItemLabel item={item} className="font-medium" />
						{hasText(item.title) && (
							<span className="block break-words text-muted-foreground">{item.title}</span>
						)}
					</span>
				</Step>
				<Step dot={{ tone: "outline", hollow: true }} at={event.occurredAt} data-step="moment">
					{onOpen === undefined ? (
						<span data-step-key="moment" className="block px-1.5 py-1">
							{eventLine}
						</span>
					) : (
						<button
							type="button"
							data-step-key="moment"
							title="Open its sheet"
							onClick={onOpen}
							className="block w-full rounded-md px-1.5 py-1 text-left hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden"
						>
							{eventLine}
						</button>
					)}
				</Step>
				<Step
					dot={{ tone: TRACE_END_TONES[verdict.end.kind] }}
					data-step="end"
					data-end={verdict.end.kind}
				>
					<p className="px-1.5 py-1">
						<span className="font-medium">{verdict.end.label}</span>: {verdict.end.reason}
					</p>
				</Step>
			</ol>
		</div>
	);
}
