import { useId } from "react";

import { cn } from "cn";
import type { ActivityOverview } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { ActionChips } from "./ActionChip";
import {
	type ActivityOverviewState,
	bucketSummary,
	type DateSpan,
	deltaPhrase,
	type PreviousPeriod,
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
import { BucketBars } from "./BucketBars";

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
	const def: ActivityCategoryDef = ACTIVITY_CATEGORY_DEFS[category];
	const Icon = def.icon(providerType);
	const headline = kindsTotal(overview.summary, def.headline.kinds);
	const chips = summaryActions(overview.summary, def.chips);
	const opens = headline > 0 || chips.length > 0;
	const delta =
		previous &&
		deltaPhrase(headline, kindsTotal(previous.summary, def.headline.kinds), previous.name);
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
					<TileChart
						category={category}
						overview={overview}
						span={span}
						providerType={providerType}
					/>
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
 * The headline alone, in the category's tone. The breakdown by kind is the category level's.
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
	return (
		<BucketBars
			rows={totalRows(overview.buckets, def.headline.kinds)}
			bucket={overview.bucket}
			span={span}
			name={capitalise(def.headline.qualifier ?? def.label(providerType))}
			fill={ACTIVITY_TONES[def.tone].fill}
		/>
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
