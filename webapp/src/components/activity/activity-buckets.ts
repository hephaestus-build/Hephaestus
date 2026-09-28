import { addDays, addMonths, format, isSameYear, max, min } from "date-fns";

import type { ActivityBucket, ActivityOverview, ActivitySummary } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { formatDayRange } from "@/lib/dates";

import { type ActivityKind, kindsTotal, type Noun } from "./activity-kind-defs";

export type BucketSize = ActivityOverview["bucket"];

/** The instants a view counts between: from inclusive, to exclusive. */
export interface DateSpan {
	from: Date;
	to: Date;
}

/** The period before the range, of the same length, that a figure is set against. */
export interface PreviousPeriod {
	summary: ActivitySummary;
	/** "the previous 30 days": the period after "than" or "as". */
	name: string;
}

/**
 * An overview once it is in: current, with the span it was read for and — once it is in too — the
 * period before it; or stale — the previous range's, standing in while the range just chosen loads,
 * for a span nobody should label it with and set against nothing.
 */
export type ActivityOverviewState = PanelState<
	{ overview: ActivityOverview } & (
		| { stale: false; span: DateSpan; previous?: PreviousPeriod }
		| { stale: true }
	)
>;

/**
 * A count set against the same count in the period before, in words and without a verdict:
 * activity going up or down is neither good nor bad here. "4 more than the previous 30 days".
 */
export function deltaPhrase(current: number, previous: number, period: string): string {
	const difference = current - previous;
	if (difference === 0) {
		return `Same as ${period}`;
	}
	return difference > 0
		? `${difference} more than ${period}`
		: `${-difference} fewer than ${period}`;
}

/** How often, on average, per bucket of the range: one decimal, "0.6". */
export function averagePerBucket(total: number, buckets: number): string {
	return (buckets === 0 ? 0 : total / buckets).toFixed(1);
}

/**
 * The first bucket as the start of a chart names it: its tick, with the year when it is not this
 * one, so twelve months read "Sep 2025" … and never "Sep" … "Sep".
 */
export function startLabel(start: Date, size: BucketSize, nowMs: number): string {
	const tick = BUCKET_SIZE_DEFS[size].tick(start);
	return isSameYear(start, nowMs) ? tick : `${tick} ${format(start, "yyyy")}`;
}

/** The span an overview's bucket labels may be clipped to: none while it is stale. */
export function readSpan(
	state: { stale: false; span: DateSpan } | { stale: true },
): DateSpan | undefined {
	return state.stale ? undefined : state.span;
}

interface BucketSizeDef {
	/** The bucket in a sentence, after "busiest". */
	noun: string;
	/** Under a bar, where width is the constraint: "23 Sep", "Sep". */
	tick: (start: Date) => string;
	/** The first instant after the bucket. */
	end: (start: Date) => Date;
}

/**
 * How a bucket reads at each size. The server starts every bucket at midnight in the browser's
 * time zone, so formatting its instant locally names the day, week or month it counted.
 */
export const BUCKET_SIZE_DEFS = {
	DAY: { noun: "day", tick: (start) => format(start, "d MMM"), end: (start) => addDays(start, 1) },
	WEEK: {
		noun: "week",
		tick: (start) => format(start, "d MMM"),
		end: (start) => addDays(start, 7),
	},
	MONTH: {
		noun: "month",
		tick: (start) => format(start, "MMM"),
		end: (start) => addMonths(start, 1),
	},
} as const satisfies Record<BucketSize, BucketSizeDef>;

/**
 * The days a bucket counted, clipped to the span it was read for: the first week of a 90-day range
 * may count only its last three days, and a bar that short is not a quiet week. "Tuesday 22
 * September" for a day, "September 2026" for a whole month, and the days themselves — "25–30 June
 * 2026" — otherwise. Without a span the bucket is named whole.
 */
export function bucketLabel(start: Date, size: BucketSize, span?: DateSpan): string {
	if (size === "DAY") {
		return format(start, "EEEE d MMMM");
	}
	const lastInstant = new Date(BUCKET_SIZE_DEFS[size].end(start).getTime() - 1);
	const first = span ? max([start, span.from]) : start;
	const last = span ? min([lastInstant, new Date(span.to.getTime() - 1)]) : lastInstant;
	const whole = first.getTime() === start.getTime() && last.getTime() === lastInstant.getTime();
	return whole && size === "MONTH" ? format(start, "MMMM yyyy") : formatDayRange(first, last);
}

/** The bucket with the most of `kinds`, the earliest on a tie; none when nothing happened. */
export function busiestBucket(
	buckets: readonly ActivityBucket[],
	kinds: readonly ActivityKind[],
): { bucket: ActivityBucket; count: number } | undefined {
	let busiest: { bucket: ActivityBucket; count: number } | undefined;
	for (const bucket of buckets) {
		const count = kindsTotal(bucket.summary, kinds);
		if (count > 0 && (busiest === undefined || count > busiest.count)) {
			busiest = { bucket, count };
		}
	}
	return busiest;
}

/**
 * A chart's words for a reader who does not see it, in actual values and no adjectives: "3 merged;
 * busiest day Tuesday 23 September, 2".
 */
export function bucketSummary(
	overview: Pick<ActivityOverview, "bucket" | "buckets" | "summary">,
	span: DateSpan | undefined,
	kinds: readonly ActivityKind[],
	unit: Noun,
): string {
	const total = kindsTotal(overview.summary, kinds);
	const headline = `${total} ${total === 1 ? unit.one : unit.many}`;
	const busiest = busiestBucket(overview.buckets, kinds);
	if (busiest === undefined) {
		return headline;
	}
	const label = bucketLabel(busiest.bucket.start, overview.bucket, span);
	return `${headline}; busiest ${BUCKET_SIZE_DEFS[overview.bucket].noun} ${label}, ${busiest.count}`;
}

/** One chart row per bucket with one column: how often any of `kinds` happened in it, together. */
/**
 * Each bucket's count and the rest of the way to the top of the scale, stacked so every bucket
 * draws a full-height track — Recharts draws nothing, not even a background, for a zero.
 */
export function trackRows(
	rows: readonly { start: number; count: number }[],
): { start: number; count: number; rest: number }[] {
	const top = Math.max(1, ...rows.map((row) => row.count));
	return rows.map((row) => ({ ...row, rest: top - row.count }));
}

export function totalRows(
	buckets: readonly ActivityBucket[],
	kinds: readonly ActivityKind[],
): { start: number; count: number }[] {
	return buckets.map(({ start, summary }) => ({
		start: start.getTime(),
		count: kindsTotal(summary, kinds),
	}));
}
