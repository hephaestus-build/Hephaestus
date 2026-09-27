import { useId } from "react";
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from "recharts";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	type ChartConfig,
	ChartContainer,
	ChartTooltip,
	ChartTooltipContent,
} from "@/components/ui/chart";
import { Skeleton } from "@/components/ui/skeleton";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { cn } from "cn";

import { ActionChips } from "./ActionChip";
import {
	type ActivityOverviewState,
	BUCKET_SIZE_DEFS,
	bucketLabel,
	bucketRows,
	bucketSummary,
	kindSeries,
	readSpan,
} from "./activity-buckets";
import {
	ACTIVITY_CATEGORY_DEFS,
	ACTIVITY_KIND_DEFS,
	type ActivityCategory,
	summaryActions,
} from "./activity-kind-defs";
import { STALE } from "./activity-tones";

export interface ActivityTrendChartProps {
	state: ActivityOverviewState;
	category: ActivityCategory;
	providerType: ProviderType;
}

/**
 * A category over the range, grown out of its tile into a chart with a date axis and a count axis
 * from zero, one group of bars per day, week or month, each kind in its own tone. The kinds of one
 * category share a unit, so they may share an axis. Where they partition the category — a review's
 * verdicts, a comment's place — they stack to the category's total; where they are steps of one
 * lifecycle — a pull request opened, then merged — they stand side by side, since a pull request
 * opened and merged in the same week is not two pull requests. The legend is the category's chips
 * with their totals, and the figure is named by a sentence saying in numbers what the bars show.
 *
 * It is drawn in the category's level, so each bar is outlined in the drawer's surface: the gap
 * that keeps two stacked kinds from reading as one bar.
 */
export function ActivityTrendChart({ state, category, providerType }: ActivityTrendChartProps) {
	const summaryId = useId();
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Couldn't load the chart"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<div aria-busy="true" className="space-y-3">
				<span className="sr-only">Loading the chart</span>
				<Skeleton aria-hidden className="h-48 w-full" />
				<Skeleton aria-hidden className="h-4 w-40" />
			</div>
		);
	}
	const { overview } = state;
	const span = readSpan(state);
	const def = ACTIVITY_CATEGORY_DEFS[category];
	const series = kindSeries(def.kinds);
	const config = Object.fromEntries(
		series.map(({ kind, fill }) => [kind, { label: ACTIVITY_KIND_DEFS[kind].label, color: fill }]),
	) satisfies ChartConfig;
	const size = BUCKET_SIZE_DEFS[overview.bucket];
	return (
		<figure
			aria-labelledby={summaryId}
			aria-busy={state.stale || undefined}
			className={cn("space-y-3", state.stale && STALE)}
		>
			<ChartContainer config={config} className="aspect-auto h-48 w-full" aria-hidden>
				<BarChart
					data={bucketRows(overview.buckets, def.kinds)}
					margin={{ top: 4, right: 0, bottom: 0, left: 0 }}
					barCategoryGap="20%"
					accessibilityLayer={false}
				>
					<CartesianGrid vertical={false} />
					<XAxis
						dataKey="start"
						tickLine={false}
						axisLine={false}
						tickMargin={8}
						minTickGap={16}
						tickFormatter={(start: number) => size.tick(new Date(start))}
					/>
					<YAxis
						width={32}
						allowDecimals={false}
						tickLine={false}
						axisLine={false}
						tickMargin={4}
					/>
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
					{series.map(({ kind, fill, fillOpacity }) => (
						<Bar
							key={kind}
							dataKey={kind}
							stackId={def.stacked ? "category" : undefined}
							fill={fill}
							fillOpacity={fillOpacity}
							stroke="var(--color-popover)"
							strokeWidth={1}
							maxBarSize={20}
							isAnimationActive={false}
						/>
					))}
				</BarChart>
			</ChartContainer>
			<figcaption className="space-y-1">
				<ActionChips
					actions={summaryActions(overview.summary, def.kinds)}
					providerType={providerType}
					display="labelled"
				/>
				<span id={summaryId} className="sr-only">
					{bucketSummary(overview, span, def.headline.kinds, def.headline.noun(providerType))}
				</span>
			</figcaption>
		</figure>
	);
}
