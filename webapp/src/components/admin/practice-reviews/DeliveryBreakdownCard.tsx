import { SplitIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { ReasonCount, ReviewFeedbackCounts } from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import { DotSeparator } from "@/components/common/DotSeparator";
import { InfoLines, InfoTip } from "@/components/common/InfoTip";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { statusToneClass } from "@/components/common/status-def";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { CardTitleLink } from "./CardTitleLink";
import {
	type BreakdownRow,
	type DeliveredCounts,
	deliveredByLocation,
	withheldByReason,
} from "./delivery-breakdown";
import { OVERVIEW_TYPE } from "./overview-type-scale";
import { type AttentionTone, percentText, shareOf } from "./review-attention";
import { PROBLEM_THRESHOLDS } from "./review-attention-thresholds";
import { type OutcomeScope, scopeSearch } from "./review-outcomes";
import type { OverviewRegionState, ReviewCountState } from "./review-states";
import type { AttentionGroup, AttentionRange } from "./ReviewAttention";
import { ReviewListLink } from "./ReviewListLink";

export type DeliveredByLocationState = PanelState<{
	counts: DeliveredCounts;
	stale: boolean;
}>;

/** Withheld feedback of the range by reason, as the table's "Why withheld" filter counts it. */
export type WithheldByReasonState = PanelState<{
	counts: ReasonCount[];
	stale: boolean;
}>;

export interface DeliveryBreakdownCardProps {
	workspaceSlug: string;
	/** The range as the lists' day filters, so every row opens the rows it counts. */
	scope: OutcomeScope;
	/** The range on screen by name: "in the last 30 days". */
	range: AttentionRange;
	/** The overview of the range, whose feedback counts hold the rejected and the withheld. */
	overview: OverviewRegionState;
	delivered: DeliveredByLocationState;
	withheld: WithheldByReasonState;
	/** Feedback of the range that failed to deliver, out of the feedback sent in it. */
	failedDeliveries: AttentionGroup<ReviewCountState>;
}

export function DeliveryBreakdownCard({
	workspaceSlug,
	scope,
	range,
	overview,
	delivered,
	withheld,
	failedDeliveries,
}: DeliveryBreakdownCardProps) {
	return (
		<Card size="sm" className="w-full" role="region" aria-label="Delivered and withheld">
			<CardHeader>
				<CardTitle className="flex items-center gap-2 text-sm font-medium">
					<SplitIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
					<CardTitleLink
						workspaceSlug={workspaceSlug}
						destination={{ list: "feedback", search: scopeSearch(scope, "feedback") }}
					>
						Delivered and withheld
					</CardTitleLink>
					<InfoTip label="About delivered and withheld feedback">
						<InfoLines lines={BREAKDOWN_INFO} />
					</InfoTip>
				</CardTitle>
			</CardHeader>
			{/* Two short tables side by side from a medium width, then the failure figure as the
			    card's foot, at the bottom of the card whatever the tile beside it holds. */}
			<CardContent className="@container flex flex-1 flex-col gap-4">
				<div className="grid grid-cols-1 gap-x-6 gap-y-4 @md:grid-cols-2">
					<BreakdownTable
						caption="Delivered"
						countTotal={({ byLocation }) => byLocation.reduce((sum, row) => sum + row.count, 0)}
						what="delivered feedback by where it appeared"
						state={delivered}
						empty={`Nothing delivered ${range.during}`}
						rows={(counts) => deliveredByLocation(counts, scope)}
						workspaceSlug={workspaceSlug}
					/>
					<BreakdownTable
						caption="Withheld by reason"
						what="withheld feedback by reason"
						state={withheldState(withheld, overview)}
						empty={`Nothing withheld ${range.during}`}
						rows={({ reasons, feedback }) => withheldByReason(reasons, feedback, scope)}
						workspaceSlug={workspaceSlug}
					/>
				</div>
				<div
					role="group"
					aria-label="Failures"
					className="mt-auto grid grid-cols-[1fr_auto] items-baseline gap-x-4 gap-y-1 border-t pt-3"
				>
					<RateLine
						workspaceSlug={workspaceSlug}
						rate={DELIVERY_FAILURE_RATE}
						group={failedDeliveries}
					/>
				</div>
			</CardContent>
		</Card>
	);
}

/** The card's (i), one short line a term, in the style of the review time's. */
const BREAKDOWN_INFO = [
	{
		term: "Delivered",
		text: "counts feedback whose delivery is complete, by where it appeared.",
	},
	{
		term: "Partly delivered",
		text: "counts feedback that appeared on the work while another part failed or is not confirmed. Its count is separate from Delivered.",
	},
	{
		term: "Withheld",
		text: "counts the feedback that was not sent, by reason. Rejected feedback is here too.",
	},
	{
		term: "Feedback failed to deliver",
		text: "is the share of the feedback sent that failed. It includes partly delivered feedback when another part failed. Above 5% it is red.",
	},
	{
		term: "Each count",
		text: "opens the table of what it counts.",
	},
];

interface WithheldCounts {
	reasons: ReasonCount[];
	feedback: Pick<ReviewFeedbackCounts, "rejected">;
}

/** The reasons read and the overview's own count of the rejected, as one state. */
function withheldState(
	withheld: WithheldByReasonState,
	overview: OverviewRegionState,
): PanelState<{ counts: WithheldCounts; stale: boolean }> {
	if (withheld.status !== "ready") {
		return withheld;
	}
	if (overview.status !== "ready") {
		return overview;
	}
	return {
		status: "ready",
		counts: { reasons: withheld.counts, feedback: overview.overview.feedback },
		stale: withheld.stale || overview.stale,
	};
}

function BreakdownTable<T>({
	caption,
	what,
	state,
	empty,
	rows,
	countTotal,
	workspaceSlug,
}: {
	caption: string;
	what: string;
	state: PanelState<{ counts: T; stale: boolean }>;
	empty: string;
	rows: (counts: T) => BreakdownRow[];
	countTotal?: (counts: T) => number;
	workspaceSlug: string;
}) {
	let body: ReactNode;
	let total: ReactNode = null;
	if (state.status === "error") {
		body = (
			<QueryErrorAlert
				error={state.error}
				title={`We could not load ${what}`}
				onRetry={state.onRetry}
			/>
		);
	} else if (state.status === "loading") {
		body = (
			<div aria-busy="true" className="flex flex-col gap-1">
				<span className="sr-only">Loading {what}…</span>
				<Skeleton className="h-4 w-full" />
				<Skeleton className="h-4 w-40" />
			</div>
		);
	} else {
		const shown = rows(state.counts);
		total = countTotal?.(state.counts) ?? shown.reduce((sum, row) => sum + row.count, 0);
		body =
			shown.length === 0 ? (
				<p className={OVERVIEW_TYPE.empty}>{empty}</p>
			) : (
				<table
					aria-label={caption}
					aria-busy={state.stale || undefined}
					className={cn("w-full table-fixed", state.stale && STALE)}
				>
					<tbody>
						{shown.map((row) => (
							<BreakdownTableRow key={row.key} row={row} workspaceSlug={workspaceSlug} />
						))}
					</tbody>
				</table>
			);
	}
	return (
		<div className="flex min-w-0 flex-col gap-1">
			<div className="flex items-baseline justify-between gap-4 border-b pb-1">
				<p className={OVERVIEW_TYPE.caption}>{caption}</p>
				{total !== null && <p className={OVERVIEW_TYPE.caption}>{total}</p>}
			</div>
			{body}
		</div>
	);
}

function BreakdownTableRow({ row, workspaceSlug }: { row: BreakdownRow; workspaceSlug: string }) {
	const Icon = row.def.icon;
	return (
		<tr className="relative hover:bg-muted/50">
			<th scope="row" className={cn("py-0.5 pr-2 text-left font-normal", OVERVIEW_TYPE.label)}>
				<span className="flex min-w-0 items-center gap-1.5">
					<Icon aria-hidden className="size-3.5 shrink-0" />
					{row.target === undefined ? (
						<span className="truncate">{row.def.label}</span>
					) : (
						<InlineLink
							className="truncate text-current after:absolute after:inset-0"
							aria-label={`${row.count}, ${row.def.label}`}
							render={<ReviewListLink workspaceSlug={workspaceSlug} destination={row.target} />}
						>
							{row.def.label}
						</InlineLink>
					)}
				</span>
			</th>
			<td className={cn("w-16 py-0.5 text-right", OVERVIEW_TYPE.value)}>{row.count}</td>
		</tr>
	);
}

interface RateDef {
	term: string;
	/** For the loading and error states: "feedback that failed to deliver". */
	what: string;
	link: (count: number, of: number | undefined) => string;
	tone: (share: number) => AttentionTone;
}

const DELIVERY_FAILURE_RATE: RateDef = {
	term: "Feedback failed to deliver",
	what: "feedback that failed to deliver",
	link: (count, of) => (of === undefined ? `${count} failed` : `${count} of ${of} sent`),
	tone: (share) =>
		share > PROBLEM_THRESHOLDS.deliveriesDestructiveAbove ? "destructive" : "secondary",
};

function RateLine({
	workspaceSlug,
	rate,
	group: { state, list, of },
}: {
	workspaceSlug: string;
	rate: RateDef;
	group: AttentionGroup<ReviewCountState>;
}) {
	if (state.status === "error") {
		return (
			<div className="col-span-2">
				<QueryErrorAlert
					error={state.error}
					title={`We could not load ${rate.what}`}
					onRetry={state.onRetry}
				/>
			</div>
		);
	}
	if (state.status === "loading") {
		return (
			<div aria-busy="true" className="col-span-2">
				<span className="sr-only">Loading {rate.what}…</span>
				<Skeleton className="h-4 w-48" />
			</div>
		);
	}
	const share = of === undefined ? undefined : shareOf(state.total, of);
	const tone: AttentionTone = share === undefined ? "secondary" : rate.tone(share);
	return (
		<div
			data-tone={tone}
			aria-busy={state.stale || undefined}
			className={cn("contents", state.stale && "[&>*]:grayscale")}
		>
			<p data-rate className={cn("min-w-0", OVERVIEW_TYPE.label)}>
				{rate.term}
				{state.total > 0 && (
					<>
						<DotSeparator inline />
						<InlineLink
							tone="count"
							render={<ReviewListLink workspaceSlug={workspaceSlug} destination={list} />}
						>
							{rate.link(state.total, of)}
						</InlineLink>
					</>
				)}
			</p>
			<p
				className={cn(
					"w-16 text-right",
					OVERVIEW_TYPE.value,
					tone !== "secondary" && statusToneClass(tone),
				)}
			>
				{share === undefined ? state.total : percentText(share)}
			</p>
		</div>
	);
}
