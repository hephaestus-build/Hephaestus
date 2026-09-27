import { useId } from "react";
import { Bar, BarChart, LabelList, XAxis, YAxis } from "recharts";

import { cn } from "cn";
import type { ActivityOverview } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { type ChartConfig, ChartContainer, ChartTooltip } from "@/components/ui/chart";
import { Skeleton } from "@/components/ui/skeleton";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { ActionChips } from "./ActionChip";
import {
	type ActivityOverviewState,
	bucketLabel,
	bucketSummary,
	type DateSpan,
	deltaPhrase,
	edgeLabels,
	trackRows,
	type PreviousPeriod,
	readSpan,
	totalRows,
} from "./activity-buckets";
import {
	BAR_RADIUS,
	TRACK_FILL,
	BarTooltip,
	CountShape,
	MAX_BAR_SIZE,
	PeakLabel,
	peakValue,
} from "./activity-chart";
import {
	ACTIVITY_CATEGORIES,
	ACTIVITY_CATEGORY_DEFS,
	type ActivityCategory,
	type ActivityCategoryDef,
	kindsTotal,
	summaryActions,
} from "./activity-kind-defs";
import { categoryLevel } from "./activity-search";
import { ACTIVITY_TONES, STALE } from "./activity-tones";

export interface ActivityTilesProps {
	state: ActivityOverviewState;
	providerType: ProviderType;
}

/**
 * One tile per kind of work, like the stat tiles of GitHub's Pulse and Apple Health's summary: the
 * headline number, its bars over the range on the tile's own scale from zero, and chips for the
 * rest. Kinds of different units never share a number or an axis. A tile opens its category's
 * level; one with nothing behind it opens nothing, since an empty list one press away is a dead end.
 */
export function ActivityTiles({ state, providerType }: ActivityTilesProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Couldn't load the summary"
				onRetry={state.onRetry}
			/>
		);
	}
	return (
		<div className="@container">
			<ul
				aria-busy={isBusy(state) || undefined}
				className={cn(
					"grid grid-cols-1 gap-3 @sm:grid-cols-2 @2xl:grid-cols-4",
					state.status === "ready" && state.stale && STALE,
				)}
			>
				{ACTIVITY_CATEGORIES.map((category) => (
					<li key={category} className="flex">
						{state.status === "ready" ? (
							<ActivityTile
								category={category}
								overview={state.overview}
								span={readSpan(state)}
								previous={state.stale ? undefined : state.previous}
								providerType={providerType}
							/>
						) : (
							<TileSkeleton />
						)}
					</li>
				))}
			</ul>
			{isBusy(state) && <span className="sr-only">Loading the summary</span>}
		</div>
	);
}

function isBusy(state: ActivityOverviewState): boolean {
	return state.status === "loading" || (state.status === "ready" && state.stale);
}

function ActivityTile({
	category,
	overview,
	span,
	previous,
	providerType,
}: {
	category: ActivityCategory;
	overview: ActivityOverview;
	/** The span the overview was read for; none while it is the previous range's. */
	span: DateSpan | undefined;
	/** The period before, once it is in; without it the tile makes no comparison. */
	previous: PreviousPeriod | undefined;
	providerType: ProviderType;
}) {
	const descriptionId = useId();
	const nowMs = useNow();
	const def: ActivityCategoryDef = ACTIVITY_CATEGORY_DEFS[category];
	const Icon = def.icon(providerType);
	const headline = kindsTotal(overview.summary, def.headline.kinds);
	const chips = summaryActions(overview.summary, def.chips);
	const opens = headline > 0 || chips.length > 0;
	const delta =
		previous &&
		deltaPhrase(headline, kindsTotal(previous.summary, def.headline.kinds), previous.name);
	const edges = edgeLabels(overview.buckets, overview.bucket, nowMs);
	const card = (
		<Card variant={opens ? "interactive" : "muted"} size="sm" className="w-full">
			<CardHeader>
				<CardTitle className="flex items-center gap-2 text-sm font-medium">
					<Icon
						size={16}
						className={cn(
							"shrink-0",
							opens ? ACTIVITY_TONES[def.tone].text : "text-muted-foreground",
						)}
					/>
					{def.label(providerType)}
				</CardTitle>
			</CardHeader>
			<CardContent className="flex flex-1 flex-col gap-3">
				<div className="space-y-1">
					<p className="flex items-baseline gap-1.5">
						<span
							className={cn(
								"text-2xl leading-none font-semibold",
								opens ? "text-foreground" : "text-muted-foreground",
							)}
						>
							{headline}
						</span>
						{def.headline.qualifier !== undefined && (
							<span className="text-sm text-muted-foreground">{def.headline.qualifier}</span>
						)}
					</p>
					{delta !== undefined && <p className="text-xs text-muted-foreground">{delta}</p>}
				</div>
				{opens && (
					<div aria-hidden className="space-y-1">
						<TileChart
							category={category}
							overview={overview}
							span={span}
							providerType={providerType}
						/>
						{edges && (
							<div className="flex justify-between text-xs text-muted-foreground">
								<span>{edges[0]}</span>
								<span>{edges[1]}</span>
							</div>
						)}
					</div>
				)}
				{chips.length > 0 && (
					<ActionChips actions={chips} providerType={providerType} display="labelled" />
				)}
			</CardContent>
		</Card>
	);
	if (!opens) {
		return card;
	}
	return (
		<>
			<DetailStackLink
				entry={categoryLevel(category)}
				aria-describedby={descriptionId}
				className={cn("flex w-full rounded-xl", FOCUS_RING)}
			>
				{card}
			</DetailStackLink>
			<span id={descriptionId} hidden>
				{[bucketSummary(overview, span, def.headline.kinds, def.headline.noun(providerType)), delta]
					.filter((part) => part !== undefined)
					.join(". ")}
			</span>
		</>
	);
}

/**
 * The headline alone, one column per bucket on this tile's own scale from zero, in the category's
 * tone, each standing in a faint full-height track so an empty day reads as present and zero. Only
 * the peak carries its value; the tile's number, its sentence and the category level's table carry
 * the rest. The breakdown by kind is the category level's. Hidden from assistive technology, which
 * reads the tile's description instead.
 */
function TileChart({
	category,
	overview,
	span,
	providerType,
}: {
	category: ActivityCategory;
	overview: ActivityOverview;
	span: DateSpan | undefined;
	providerType: ProviderType;
}) {
	const def: ActivityCategoryDef = ACTIVITY_CATEGORY_DEFS[category];
	const { fill } = ACTIVITY_TONES[def.tone];
	const name = capitalise(def.headline.qualifier ?? def.label(providerType));
	const rows = trackRows(totalRows(overview.buckets, def.headline.kinds));
	const config = { count: { label: name, color: fill } } satisfies ChartConfig;
	return (
		<ChartContainer config={config} className="aspect-auto h-16 w-full">
			<BarChart
				data={rows}
				margin={{ top: 16, right: 4, bottom: 0, left: 4 }}
				barCategoryGap="20%"
				accessibilityLayer={false}
			>
				<XAxis dataKey="start" hide />
				<YAxis hide domain={[0, "dataMax"]} />
				<ChartTooltip
					cursor={false}
					content={
						<BarTooltip
							name={name}
							bucketLabel={(start) => capitalise(bucketLabel(start, overview.bucket, span))}
						/>
					}
				/>
				<Bar
					dataKey="count"
					stackId="track"
					fill={fill}
					maxBarSize={MAX_BAR_SIZE}
					shape={<CountShape />}
					isAnimationActive={false}
				>
					<LabelList valueAccessor={peakValue(rows)} content={<PeakLabel />} />
				</Bar>
				<Bar
					dataKey="rest"
					stackId="track"
					fill={TRACK_FILL}
					radius={BAR_RADIUS}
					maxBarSize={MAX_BAR_SIZE}
					isAnimationActive={false}
				/>
			</BarChart>
		</ChartContainer>
	);
}

/** A tile's shape while the summary loads: the title, the number, the bars and a chip. */
function TileSkeleton() {
	return (
		<Card size="sm" className="w-full" aria-hidden>
			<CardHeader>
				<Skeleton className="h-4 w-24" />
			</CardHeader>
			<CardContent className="flex flex-col gap-3">
				<Skeleton className="h-6 w-16" />
				<Skeleton className="h-16 w-full" />
				<Skeleton className="h-4 w-20" />
			</CardContent>
		</Card>
	);
}
