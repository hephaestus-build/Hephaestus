import { Bar, BarChart, LabelList, XAxis, YAxis } from "recharts";

import { useNow } from "@/components/common/use-now";
import { type ChartConfig, ChartContainer, ChartTooltip } from "@/components/ui/chart";
import { capitalise } from "@/lib/text";

import {
	type BucketSize,
	bucketLabel,
	type DateSpan,
	startLabel,
	trackRows,
} from "./activity-buckets";
import {
	BAR_RADIUS,
	BarTooltip,
	CountShape,
	MAX_BAR_SIZE,
	PeakLabel,
	peakValue,
	TRACK_FILL,
} from "./activity-chart";

export interface BucketBarsProps {
	/** One count per bucket, oldest first, each keyed by its start in milliseconds. */
	rows: readonly { start: number; count: number }[];
	bucket: BucketSize;
	/** The span the counts were read for, which the tooltip clips a partial bucket to. */
	span: DateSpan | undefined;
	/** What a bar counts, in the tooltip: "Merged", "Reviews". */
	name: string;
	/** The bars' colour: a theme token, which answers to `.dark` itself. */
	fill: string;
}

/**
 * A stat tile's chart: one column per bucket on its own scale from zero, each standing in a faint
 * full-height track so an empty day reads as present and zero, over the range's first bucket and
 * "Today". Only the peak carries its value; the tile's number and its sentence carry the rest, so
 * the chart is hidden from assistive technology.
 */
export function BucketBars({ rows, bucket, span, name, fill }: BucketBarsProps) {
	const nowMs = useNow();
	const tracked = trackRows(rows);
	const first = rows.at(0);
	const config = { count: { label: name, color: fill } } satisfies ChartConfig;
	return (
		<div aria-hidden className="space-y-1">
			<ChartContainer config={config} className="aspect-auto h-16 w-full">
				<BarChart
					data={tracked}
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
								bucketLabel={(start) => capitalise(bucketLabel(start, bucket, span))}
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
						<LabelList valueAccessor={peakValue(tracked)} content={<PeakLabel />} />
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
			{first !== undefined && (
				<div className="flex justify-between text-xs text-muted-foreground">
					<span>{startLabel(new Date(first.start), bucket, nowMs)}</span>
					<span>Today</span>
				</div>
			)}
		</div>
	);
}
