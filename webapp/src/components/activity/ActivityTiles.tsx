import { useId } from "react";
import { Bar, BarChart, ReferenceLine, XAxis, YAxis } from "recharts";

import { cn } from "cn";
import type { ActivityOverview } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
	type ChartConfig,
	ChartContainer,
	ChartTooltip,
	ChartTooltipContent,
} from "@/components/ui/chart";
import { Skeleton } from "@/components/ui/skeleton";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { ActionChips } from "./ActionChip";
import {
	type ActivityOverviewState,
	bucketLabel,
	bucketSummary,
	type DateSpan,
	readSpan,
	totalRows,
} from "./activity-buckets";
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
	providerType,
}: {
	category: ActivityCategory;
	overview: ActivityOverview;
	/** The span the overview was read for; none while it is the previous range's. */
	span: DateSpan | undefined;
	providerType: ProviderType;
}) {
	const descriptionId = useId();
	const def: ActivityCategoryDef = ACTIVITY_CATEGORY_DEFS[category];
	const Icon = def.icon(providerType);
	const headline = kindsTotal(overview.summary, def.headline.kinds);
	const chips = summaryActions(overview.summary, def.chips);
	const opens = headline > 0 || chips.length > 0;
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
				<p className="flex items-baseline gap-1.5">
					<span
						className={cn(
							"text-2xl leading-none font-semibold tabular-nums",
							opens ? "text-foreground" : "text-muted-foreground",
						)}
					>
						{headline}
					</span>
					{def.headline.qualifier !== undefined && (
						<span className="text-sm text-muted-foreground">{def.headline.qualifier}</span>
					)}
				</p>
				<div className="h-12">
					{headline > 0 && (
						<TileChart
							category={category}
							overview={overview}
							span={span}
							providerType={providerType}
						/>
					)}
				</div>
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
				{bucketSummary(overview, span, def.headline.kinds, def.headline.noun(providerType))}
			</span>
		</>
	);
}

/**
 * The headline alone, one bar per bucket on this tile's own scale from zero, in the category's tone
 * — its state's where it has one, neutral where the headline sums several states, since a colour
 * there would claim a state the bars do not have. The breakdown by kind is the category level's
 * chart. No axes: the tile's number and its sentence carry the values, and the bars are hidden from
 * assistive technology, which reads the tile's description instead.
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
	const config = {
		count: { label: capitalise(def.headline.qualifier ?? def.label(providerType)), color: fill },
	} satisfies ChartConfig;
	return (
		<ChartContainer config={config} className="aspect-auto h-12 w-full" aria-hidden>
			<BarChart
				data={totalRows(overview.buckets, def.headline.kinds)}
				margin={{ top: 2, right: 0, bottom: 0, left: 0 }}
				barCategoryGap="20%"
				accessibilityLayer={false}
			>
				<XAxis dataKey="start" hide />
				<YAxis hide domain={[0, "dataMax"]} />
				{/* The zero every bar stands on, so an empty day reads as none rather than missing. */}
				<ReferenceLine y={0} stroke="var(--color-border)" />
				<ChartTooltip
					cursor={false}
					content={
						<ChartTooltipContent
							labelFormatter={(label) =>
								typeof label === "number"
									? capitalise(bucketLabel(new Date(label), overview.bucket, span))
									: null
							}
						/>
					}
				/>
				<Bar dataKey="count" fill={fill} maxBarSize={10} isAnimationActive={false} />
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
				<Skeleton className="h-12 w-full" />
				<Skeleton className="h-4 w-20" />
			</CardContent>
		</Card>
	);
}
