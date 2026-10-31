import { PanelRightOpenIcon } from "lucide-react";
import { createContext, type ReactElement, type ReactNode, use } from "react";

import { cn } from "cn";
import type { ReviewDetail, ReviewLineage } from "@/api/types.gen";
import type { ReviewOrigin } from "@/components/practice-vocabulary/review-origin-defs";

import type { NumberedById, NumberedResult } from "./lineage-chain";
import type { RailItem } from "./lineage-story";
import { HereBadge, hereFrame } from "./LineageStep";
import type { RecordRef } from "./review-layers";
import { sameTraceNode, type TraceNode, type Traced, traceStepKey } from "./review-trace";
import type { TraceBottleneck } from "./trace-bottleneck";
import type { TraceDisplay } from "./trace-display";
import type { TraceFilter } from "./trace-filter";

/** What every step of the trace reads from the story it is drawn in. */
export interface StoryContext {
	here: TraceNode | undefined;
	/** The record a step opens, or undefined where it opens none. */
	recordOf: (node: TraceNode) => RecordRef | undefined;
	open: ((record: RecordRef) => void) | undefined;
	/** The facts of a step, drawn under the record the reader started from where system info shows, or under every step. */
	facts: (node: TraceNode) => ReactElement | null;
	display: TraceDisplay;
	/** Whether every step shows its facts, as "Expand all" asks. */
	factsOpen: boolean;
	/** How the review came to run, from its row. */
	origin: ReviewOrigin | undefined;
	review: ReviewDetail | undefined;
	/** Where the review stands among the reviews of its work. */
	position: { index: number; total: number } | undefined;
	/** Where the path was lost or slowed, which its stage's header marks. */
	bottleneck: TraceBottleneck | undefined;
}

export const StoryNodes = createContext<StoryContext | undefined>(undefined);

export function useStory(): StoryContext {
	const context = use(StoryNodes);
	if (context === undefined) {
		throw new Error("A story step renders inside ReviewLineageGraph");
	}
	return context;
}

export function offPath(traced: Traced | undefined, node: TraceNode): boolean {
	if (traced === undefined) {
		return false;
	}
	if (node.kind === "result") {
		return !traced.results.has(node.id);
	}
	if (node.kind === "feedback") {
		return !traced.feedback.has(node.id);
	}
	return false;
}

// ------------------------------------------------------------------------------------------------
// A step the reader can stand on and open
// ------------------------------------------------------------------------------------------------

export function StoryNode({
	node,
	className,
	after,
	row = false,
	children,
}: {
	node: TraceNode;
	className?: string;
	after?: ReactNode;
	/** A row of a table in a stage: flush with its neighbours, its columns saying what "Open" would. */
	row?: boolean;
	children: ReactNode;
}) {
	const context = useStory();
	const key = traceStepKey(node);
	const here = sameTraceNode(context.here, node);
	const record = context.recordOf(node);
	const { open } = context;
	const card = cn(
		"block w-full min-w-0 text-left focus-visible:outline-hidden",
		row ? "rounded-none" : "rounded-md",
		here && hereFrame(row),
		className,
	);
	const body =
		open !== undefined && record !== undefined ? (
			<button
				type="button"
				data-step-control
				aria-current={here ? "step" : undefined}
				title="Open its sheet"
				onClick={() => {
					open(record);
				}}
				className={cn(
					card,
					"group/open hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-ring",
					row ? "focus-visible:-outline-offset-2 focus-visible:ring-inset" : "relative pr-14",
				)}
			>
				{children}
				{!row && (
					<span
						aria-hidden
						className="absolute top-1 right-1.5 hidden items-center gap-0.5 font-medium text-primary group-hover/open:inline-flex group-focus-visible/open:inline-flex"
					>
						Open
						<PanelRightOpenIcon aria-hidden className="size-3.5" />
					</span>
				)}
			</button>
		) : (
			<div
				data-step-control
				tabIndex={-1}
				aria-current={here ? "step" : undefined}
				className={card}
			>
				{children}
			</div>
		);
	// Only what the step's own line does not say already: a step with nothing more draws no box.
	const facts = (here && context.display.system) || context.factsOpen ? context.facts(node) : null;
	return (
		<div data-step-key={key} data-lineage-node={key} data-here={here} className="min-w-0">
			{here && <HereMarker inset={row} />}
			{body}
			{after}
			{facts !== null && (
				<div
					data-step-facts
					className={cn(
						"rounded-md border border-dashed bg-muted/30 px-2.5 py-2 text-xs",
						row ? "mx-1.5 mb-1.5 ml-[2.125rem]" : "mt-1.5",
					)}
				>
					{facts}
				</div>
			)}
		</div>
	);
}

function HereMarker({ inset = false }: { inset?: boolean }) {
	return (
		<p className={cn("mb-1", inset && "px-1.5 pt-1.5")}>
			<HereBadge />
		</p>
	);
}

export interface StepProps {
	lineage: ReviewLineage;
	full: ReviewLineage;
	filter: TraceFilter;
	numbered: NumberedResult[];
	self: NumberedResult | undefined;
	path: NumberedResult[] | undefined;
	listed: boolean;
	rows: NumberedResult[];
	onListedChange: (open: boolean) => void;
	byId: NumberedById;
	letters: ReadonlyMap<string, string>;
	traced: Traced | undefined;
	here: TraceNode | undefined;
	hasStart: boolean;
	othersOpen: boolean;
	onOthersOpenChange: (open: boolean) => void;
	onShowSiblings: () => void;
}

export interface RailStepProps extends StepProps {
	item: RailItem;
	previous: Date | undefined;
}
