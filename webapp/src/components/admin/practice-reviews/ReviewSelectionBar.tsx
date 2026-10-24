import { RouteIcon, XIcon } from "lucide-react";

import type { PanelState } from "@/components/common/panel-state";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Button, buttonVariants } from "@/components/ui/button";
import { ButtonGroup, ButtonGroupText } from "@/components/ui/button-group";
import { Skeleton } from "@/components/ui/skeleton";

import { type RecordRef, traceLevel } from "./review-layers";
import type { ReviewSelectionKind } from "./review-selection";

/** The selection's name, read from its record: never its id, which no reader knows it by. */
export type ReviewSelectionName = PanelState<{ name: string }>;

export interface ReviewSelectionBarProps {
	kind: ReviewSelectionKind;
	name: ReviewSelectionName;
	/** The tracked record, whose trace "Show trace" opens. */
	trace?: RecordRef;
	onClear: () => void;
	/** Said beside the selection on a tab that does not narrow by it. */
	note?: string;
}

const TRACE_BUTTON = buttonVariants({ variant: "outline", size: "sm", className: "h-8" });

const UNNAMED: Record<ReviewSelectionKind, string> = {
	item: "a piece of work",
	review: "a review",
	result: "an observation",
	feedback: "a piece of feedback",
	practice: "a practice",
};

/**
 * The one record tracked from a row, above every tab, so the reader always sees what the tables are
 * narrowed to and can stop tracking it in one step. "Show trace" opens the record's trace over the
 * tab.
 */
export function ReviewSelectionBar({ kind, name, trace, onClear, note }: ReviewSelectionBarProps) {
	const shown = name.status === "ready" ? name.name : UNNAMED[kind];
	return (
		<div
			role="group"
			aria-label="Selection"
			className="flex min-w-0 flex-wrap items-center gap-2 text-sm"
		>
			<ButtonGroup className="min-w-0">
				<ButtonGroupText className="h-8 max-w-[70vw] min-w-0 gap-1.5 sm:max-w-md">
					<span className="shrink-0 text-muted-foreground">Tracking:</span>
					{name.status === "loading" ? (
						<>
							<Skeleton aria-hidden className="h-4 w-40 max-w-full" />
							<span className="sr-only">{UNNAMED[kind]}</span>
						</>
					) : (
						<span className="truncate font-medium" title={shown}>
							{shown}
						</span>
					)}
				</ButtonGroupText>
				<Button
					variant="outline"
					size="sm"
					className="h-8"
					aria-label={`Stop tracking (${shown})`}
					onClick={onClear}
				>
					<XIcon aria-hidden />
				</Button>
			</ButtonGroup>
			{trace !== undefined && (
				<DetailStackLink
					entry={traceLevel(trace)}
					single
					levelSearch={{ focus: undefined, queue: undefined }}
					className={TRACE_BUTTON}
				>
					<RouteIcon aria-hidden data-icon="inline-start" />
					Show trace
				</DetailStackLink>
			)}
			{note !== undefined && <span className="text-xs text-muted-foreground">{note}</span>}
		</div>
	);
}
