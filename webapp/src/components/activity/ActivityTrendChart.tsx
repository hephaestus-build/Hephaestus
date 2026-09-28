import { ChevronRightIcon } from "@primer/octicons-react";
import { useId } from "react";
import { Bar, BarChart, ReferenceLine, XAxis, YAxis } from "recharts";

import { cn } from "cn";
import type { ActivityOverview } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { type ChartConfig, ChartContainer, ChartTooltip } from "@/components/ui/chart";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import {
	type ActivityOverviewState,
	averagePerBucket,
	BUCKET_SIZE_DEFS,
	bucketLabel,
	bucketSummary,
	busiestBucket,
	type DateSpan,
	readSpan,
	startLabel,
	totalRows,
} from "./activity-buckets";
import { BAR_RADIUS, BarTooltip, MAX_BAR_SIZE } from "./activity-chart";
import {
	ACTIVITY_CATEGORY_DEFS,
	ACTIVITY_KIND_DEFS,
	type ActivityCategory,
	type ActivityCategoryDef,
	type ActivityKind,
	kindsTotal,
} from "./activity-kind-defs";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import { ACTIVITY_TONES, markTone, STALE } from "./activity-tones";

export interface ActivityTrendChartProps {
	state: ActivityOverviewState;
	category: ActivityCategory;
	/** The range, for what an empty row says: "None in the last 30 days". */
	range: ActivityRange;
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
export function ActivityTrendChart({
	state,
	category,
	range,
	providerType,
}: ActivityTrendChartProps) {
	const nowMs = useNow();
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
			<div aria-busy="true" className="space-y-4">
				<span className="sr-only">Loading the chart</span>
				<Skeleton aria-hidden className="h-12 w-full" />
				<Skeleton aria-hidden className="h-48 w-full" />
			</div>
		);
	}
	const { overview } = state;
	const span = readSpan(state);
	const previous = state.stale ? undefined : state.previous;
	const def: ActivityCategoryDef = ACTIVITY_CATEGORY_DEFS[category];
	const size = BUCKET_SIZE_DEFS[overview.bucket];
	const headline = kindsTotal(overview.summary, def.headline.kinds);
	const busiest = busiestBucket(overview.buckets, def.headline.kinds);
	const difference = previous && headline - kindsTotal(previous.summary, def.headline.kinds);
	const scaleMax = Math.max(
		0,
		...def.kinds.flatMap((kind) => totalRows(overview.buckets, [kind]).map((row) => row.count)),
	);
	const label = (start: Date) => capitalise(bucketLabel(start, overview.bucket, span));
	return (
		<div aria-busy={state.stale || undefined} className={cn("space-y-5", state.stale && STALE)}>
			<dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
				<Figure term={capitalise(def.headline.qualifier ?? "total")} value={String(headline)} />
				<Figure
					term={`Average per ${size.noun}`}
					value={averagePerBucket(headline, overview.buckets.length)}
				/>
				{busiest && (
					<Figure
						term={`Busiest ${size.noun}`}
						value={String(busiest.count)}
						note={label(busiest.bucket.start)}
					/>
				)}
				{previous && difference !== undefined && (
					<Figure term={`vs ${previous.name}`} value={signed(difference)} />
				)}
			</dl>
			<figure aria-labelledby={summaryId} className="space-y-4">
				<ul className="space-y-4">
					{def.kinds.map((kind, index) => (
						<KindRow
							key={kind}
							kind={kind}
							overview={overview}
							scaleMax={scaleMax}
							label={label}
							none={`None in ${ACTIVITY_RANGE_DEFS[range].inSentence}`}
							nowMs={nowMs}
							providerType={providerType}
							last={index === def.kinds.length - 1}
						/>
					))}
				</ul>
				<figcaption id={summaryId} className="sr-only">
					{bucketSummary(overview, span, def.headline.kinds, def.headline.noun(providerType))}
				</figcaption>
			</figure>
			<BucketTable def={def} overview={overview} span={span} providerType={providerType} />
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
					<span className="ml-1.5 text-xs font-normal text-muted-foreground">{note}</span>
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
	kind,
	overview,
	scaleMax,
	label,
	none,
	nowMs,
	providerType,
	last,
}: {
	kind: ActivityKind;
	overview: ActivityOverview;
	scaleMax: number;
	label: (start: Date) => string;
	/** What the row says in its plot when the kind did not happen at all. */
	none: string;
	nowMs: number;
	providerType: ProviderType;
	last: boolean;
}) {
	const def = ACTIVITY_KIND_DEFS[kind];
	const Icon = def.icon(providerType);
	const tone = ACTIVITY_TONES[markTone(def.tone)];
	const rows = totalRows(overview.buckets, [kind]);
	const config = { count: { label: def.label, color: tone.fill } } satisfies ChartConfig;
	const { tick } = BUCKET_SIZE_DEFS[overview.bucket];
	const total = kindsTotal(overview.summary, [kind]);
	return (
		<li className="space-y-1">
			<p className="flex items-center gap-2 text-sm">
				<Icon size={16} className={cn("shrink-0", tone.text)} />
				{def.label}
				<span className="font-semibold text-foreground tabular-nums">{total}</span>
			</p>
			<div className="relative">
				{total === 0 && (
					<p className="absolute inset-x-0 top-0 flex h-14 items-center text-xs text-muted-foreground">
						{none}
					</p>
				)}
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
									? startLabel(new Date(start), overview.bucket, nowMs)
									: tick(new Date(start))
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
			</div>
		</li>
	);
}

/**
 * The charts as a table, one row per bucket and one column per kind — and a total where the kinds
 * add up to one — for a reader who cannot or would rather not point at bars.
 */
function BucketTable({
	def,
	overview,
	span,
	providerType,
}: {
	def: ActivityCategoryDef;
	overview: ActivityOverview;
	span: DateSpan | undefined;
	providerType: ProviderType;
}) {
	const size = BUCKET_SIZE_DEFS[overview.bucket];
	return (
		<Collapsible>
			<CollapsibleTrigger render={<Button variant="ghost" size="sm" className="group -ml-2" />}>
				<ChevronRightIcon
					size={16}
					className="transition-transform group-aria-expanded:rotate-90 motion-reduce:transition-none"
				/>
				Show as table
			</CollapsibleTrigger>
			<CollapsibleContent className="pt-2">
				<Table bordered aria-label={`${def.label(providerType)} by ${size.noun}`}>
					<TableHeader>
						<TableRow variant="static">
							<TableHead>{capitalise(size.noun)}</TableHead>
							{def.kinds.map((kind) => (
								<TableHead key={kind} numeric className="text-right">
									{ACTIVITY_KIND_DEFS[kind].label}
								</TableHead>
							))}
							{def.partitioned && (
								<TableHead numeric className="text-right">
									Total
								</TableHead>
							)}
						</TableRow>
					</TableHeader>
					<TableBody>
						{overview.buckets.map((bucket) => (
							<TableRow key={bucket.start.getTime()} variant="static">
								<TableCell>
									{capitalise(bucketLabel(bucket.start, overview.bucket, span))}
								</TableCell>
								{def.kinds.map((kind) => (
									<CountCell key={kind} count={kindsTotal(bucket.summary, [kind])} />
								))}
								{def.partitioned && <CountCell count={kindsTotal(bucket.summary, def.kinds)} />}
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
