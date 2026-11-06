import { ChevronRightIcon } from "lucide-react";
import { useId } from "react";
import { Bar, BarChart, ReferenceLine, XAxis, YAxis } from "recharts";
import { definitionFor } from "@/components/common/status-def";

import { cn } from "cn";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { type ChartConfig, ChartContainer, ChartTooltip } from "@/components/ui/chart";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Empty, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { formatUtcShortDay } from "@/lib/dates";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise, hasText } from "@/lib/text";

import {
	averagePerBucket,
	BUCKET_SIZE_DEFS,
	busiestWeek,
	type DateSpan,
	utcWeekLabel,
	utcWeekStartLabel,
	countedWeeksSummary,
} from "./activity-buckets";
import type { ActivityCategoryChart, ActivityCategoryChartState } from "./activity-category-chart";
import { BAR_RADIUS, BarTooltip, MAX_BAR_SIZE } from "./activity-chart";
import {
	UNKNOWN_ACTIVITY_KIND_DEF,
	ACTIVITY_CATEGORY_DEFS,
	ACTIVITY_KIND_DEFS,
	type ActivityCategoryDef,
} from "./activity-kind-defs";
import { ACTIVITY_TONES, markTone, STALE } from "./activity-tones";

export interface ActivityTrendChartProps {
	state: ActivityCategoryChartState;
	providerType: ProviderType;
}

/**
 * A category over the range, grown out of its tile: its figures first — the total, the average per
 * bucket, the busiest bucket and the change on the period before — then one small chart per kind,
 * each a single series in the kind's tone on a scale shared by every row, since they count the
 * same unit. Small multiples rather than stacks or groups: kinds in one bar hide each other's
 * shape, grouped bars for thirty days are too thin to read, and an approval's green beside a
 * change request's red is a pair a red-green colour-blind reader cannot tell apart. Every value is
 * also in the table under the charts, which is the path that needs no pointer.
 */
export function ActivityTrendChart({ state, providerType }: ActivityTrendChartProps) {
	const nowMs = useNow();
	const summaryId = useId();
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load the chart"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<div aria-busy="true" className="space-y-4">
				<span className="sr-only">Loading the chart…</span>
				<Skeleton aria-hidden className="h-12 w-full" />
				<Skeleton aria-hidden className="h-48 w-full" />
			</div>
		);
	}
	const { chart } = state;
	const span = state.stale ? undefined : state.span;
	const previous = state.stale ? undefined : state.previous;
	const def: ActivityCategoryDef = ACTIVITY_CATEGORY_DEFS[chart.category];
	const size = BUCKET_SIZE_DEFS.WEEK;
	const headline = chart.total;
	const busiest = busiestWeek(chart.weeks);
	const difference = previous && headline - previous.total;
	const scaleMax = Math.max(
		0,
		...chart.weeks.flatMap((week) => week.kinds.map(({ count }) => count)),
	);
	const plottedKinds = chart.kinds
		.filter(
			({ kind, count }) =>
				count !== 0 ||
				chart.weeks.some((week) =>
					week.kinds.some((value) => value.kind === kind && value.count !== 0),
				),
		)
		.map(({ kind }) => kind);
	const lastPlottedKind = plottedKinds.at(-1);
	const label = (start: Date) => capitalise(utcWeekLabel(start, span));
	return (
		<div aria-busy={state.stale || undefined} className={cn("space-y-5", state.stale && STALE)}>
			<dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
				<Figure term={capitalise(def.headline.qualifier ?? "total")} value={String(headline)} />
				<Figure
					term={`Average per ${size.noun}`}
					value={averagePerBucket(headline, chart.weeks.length)}
				/>
				{busiest && (
					<Figure
						term={`Busiest ${size.noun}`}
						value={String(busiest.count)}
						note={label(busiest.week.start)}
					/>
				)}
				{previous && difference !== undefined && (
					<Figure term={`vs ${previous.name}`} value={signed(difference)} />
				)}
			</dl>
			<figure aria-labelledby={summaryId} className="space-y-4">
				<p className="text-xs text-muted-foreground">Weeks use UTC.</p>
				<ul className="space-y-4">
					{chart.kinds.map((series) => (
						<KindRow
							key={series.kind}
							series={series}
							weeks={chart.weeks}
							scaleMax={scaleMax}
							label={label}
							plot={plottedKinds.includes(series.kind)}
							nowMs={nowMs}
							providerType={providerType}
							last={series.kind === lastPlottedKind}
						/>
					))}
				</ul>
				{plottedKinds.length === 0 && (
					<Empty>
						<EmptyHeader>
							<EmptyTitle>None in this range</EmptyTitle>
						</EmptyHeader>
					</Empty>
				)}
				<figcaption id={summaryId} className="sr-only">
					{countedWeeksSummary(chart.total, chart.weeks, span, def.headline.noun(providerType))}
				</figcaption>
			</figure>
			<BucketTable def={def} chart={chart} span={span} providerType={providerType} />
		</div>
	);
}

/** "+4", "−2", "0": a change as a figure writes it, with a true minus sign. */
function signed(difference: number): string {
	if (difference === 0) {
		return "0";
	}
	return difference > 0 ? `+${difference}` : `−${-difference}`;
}

/** One figure: what it is, muted, over its value, strong. */
function Figure({ term, value, note }: { term: string; value: string; note?: string }) {
	return (
		<div className="min-w-0 space-y-0.5">
			<dt className="text-xs text-muted-foreground">{term}</dt>
			<dd className="text-lg leading-tight font-semibold text-foreground">
				{value}
				{note !== undefined && (
					<span className="ml-1.5 text-xs font-normal text-muted-foreground">
						<span className="sr-only"> in </span>
						{note}
					</span>
				)}
			</dd>
		</div>
	);
}

/**
 * One kind's row: its icon, name and total, over its columns on the category's shared scale, with
 * a hairline at zero and at the scale's top, the top's value beside it, and the dates under the
 * last row only.
 */
function KindRow({
	series,
	weeks,
	scaleMax,
	label,
	plot,
	nowMs,
	providerType,
	last,
}: {
	series: ActivityCategoryChart["kinds"][number];
	weeks: ActivityCategoryChart["weeks"];
	scaleMax: number;
	label: (start: Date) => string;
	plot: boolean;
	nowMs: number;
	providerType: ProviderType;
	last: boolean;
}) {
	const def = definitionFor(ACTIVITY_KIND_DEFS, series.kind, UNKNOWN_ACTIVITY_KIND_DEF);
	const Icon = def.icon(providerType);
	// The icon keeps the kind's own colour, as everywhere else; only its bars take a mark tone.
	const tone = ACTIVITY_TONES[markTone(def.tone)];
	const rows = weeks.flatMap((week) =>
		week.kinds
			.filter(({ kind }) => kind === series.kind)
			.map(({ count }) => ({ start: week.start.getTime(), count })),
	);
	const config = { count: { label: def.label, color: tone.fill } } satisfies ChartConfig;
	const total = series.count;
	return (
		<li className="space-y-1">
			<p className="flex items-center gap-2 text-sm">
				<Icon size={16} className={cn("shrink-0", ACTIVITY_TONES[def.tone].text)} />
				{def.label}
				<span className="font-semibold text-foreground tabular-nums">{total}</span>
			</p>
			{plot && (
				<ChartContainer
					config={config}
					className={cn("aspect-auto w-full", last ? "h-24" : "h-16")}
					aria-hidden
				>
					<BarChart
						data={rows}
						margin={{ top: 8, right: 28, bottom: 0, left: 0 }}
						barCategoryGap="12%"
						accessibilityLayer={false}
					>
						<YAxis hide domain={[0, Math.max(scaleMax, 1)]} />
						<XAxis
							dataKey="start"
							hide={!last}
							tickLine={false}
							axisLine={false}
							tickMargin={6}
							minTickGap={24}
							tickFormatter={(start: number, index: number) =>
								// The first tick carries the year when it is not this one, so a year of months
								// never reads "Sep" … "Sep".
								index === 0
									? utcWeekStartLabel(new Date(start), nowMs)
									: formatUtcShortDay(new Date(start))
							}
						/>
						<ReferenceLine y={0} stroke="var(--color-border)" />
						{scaleMax > 0 && total > 0 && (
							<ReferenceLine
								y={scaleMax}
								stroke="var(--color-border)"
								label={{
									value: scaleMax,
									position: "right",
									className: "fill-muted-foreground text-xs tabular-nums",
								}}
							/>
						)}
						<ChartTooltip
							cursor={{ fill: "var(--color-muted)" }}
							content={<BarTooltip name={def.label} bucketLabel={label} />}
						/>
						<Bar
							dataKey="count"
							fill={tone.fill}
							radius={BAR_RADIUS}
							maxBarSize={MAX_BAR_SIZE}
							isAnimationActive={false}
						/>
					</BarChart>
				</ChartContainer>
			)}
		</li>
	);
}

/**
 * The charts as a table, one row per week and one column per kind — and the headline's own column
 * where it is not one of the kinds — for a reader who cannot or would rather not point at bars.
 */
function BucketTable({
	def,
	chart,
	span,
	providerType,
}: {
	def: ActivityCategoryDef;
	chart: ActivityCategoryChart;
	span: DateSpan | undefined;
	providerType: ProviderType;
}) {
	const size = BUCKET_SIZE_DEFS.WEEK;
	return (
		<Collapsible>
			<CollapsibleTrigger render={<Button variant="ghost" size="sm" className="group -ml-2" />}>
				<ChevronRightIcon
					aria-hidden
					className="size-4 transition-transform group-aria-expanded:rotate-90 motion-reduce:transition-none"
				/>
				Show as table
			</CollapsibleTrigger>
			<CollapsibleContent className="pt-2">
				<Table bordered aria-label={`${def.label(providerType)} by ${size.noun}`}>
					<TableHeader>
						<TableRow variant="static">
							<TableHead className="sticky left-0 z-20 bg-card">
								<span className="block min-w-28 wrap-anywhere whitespace-normal">
									{capitalise(size.noun)}
								</span>
							</TableHead>
							{chart.kinds.map(({ kind }) => (
								<TableHead key={kind} numeric className="text-right">
									{definitionFor(ACTIVITY_KIND_DEFS, kind, UNKNOWN_ACTIVITY_KIND_DEF).label}
								</TableHead>
							))}
							{hasText(def.headline.column) && (
								<TableHead numeric className="text-right">
									{def.headline.column}
								</TableHead>
							)}
						</TableRow>
					</TableHeader>
					<TableBody>
						{chart.weeks.map((week) => (
							<TableRow key={week.start.getTime()} variant="static">
								<TableCell className="sticky left-0 z-20 bg-card">
									<span className="block min-w-28 wrap-anywhere whitespace-normal">
										{capitalise(utcWeekLabel(week.start, span))}
									</span>
								</TableCell>
								{week.kinds.map(({ kind, count }) => (
									<CountCell key={kind} count={count} />
								))}
								{hasText(def.headline.column) && <CountCell count={week.count} />}
							</TableRow>
						))}
					</TableBody>
				</Table>
			</CollapsibleContent>
		</Collapsible>
	);
}

/** A count in the table: a zero stays, for a reader who reads every cell, but steps back in grey. */
function CountCell({ count }: { count: number }) {
	return (
		<TableCell numeric className={cn("text-right", count === 0 && "text-muted-foreground")}>
			{count}
		</TableCell>
	);
}
