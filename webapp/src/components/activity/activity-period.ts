import { differenceInCalendarDays, subDays } from "date-fns";

import type { GetActivityPeopleData } from "@/api/types.gen";
import type { FilterOption } from "@/components/common/FilterToggle";
import {
	dayAfterInstant,
	dayStartInstant,
	fromDayParam,
	toDayParam,
} from "@/lib/date-range-search";
import { formatDayRange } from "@/lib/dates";

import { ACTIVITY_RANGE_DEFS, ACTIVITY_RANGES } from "./activity-range";

/** The ranges one press picks: the rolling windows that end now, and all of the history. */
export const ACTIVITY_PRESETS = [...ACTIVITY_RANGES, "all"] as const;

export type ActivityPreset = (typeof ACTIVITY_PRESETS)[number];

export const DEFAULT_ACTIVITY_PRESET = "90d" satisfies ActivityPreset;

/**
 * The first day a custom range may start on: no provider history is older, and a day centuries
 * back would only draw tens of thousands of empty weeks.
 */
export const EARLIEST_CUSTOM_DAY = new Date(2000, 0, 1);

/** What activity counts: a preset, or the days from one to another, both included, in local time. */
export type ActivityPeriod =
	| { kind: "preset"; preset: ActivityPreset }
	| { kind: "custom"; from: Date; to: Date };

const PRESET_LABELS = {
	...ACTIVITY_RANGE_DEFS,
	all: { label: "All time", shortLabel: "All time", previous: undefined },
} as const satisfies Record<
	ActivityPreset,
	{ label: string; shortLabel: string; previous: string | undefined }
>;

export const ACTIVITY_PRESET_OPTIONS: readonly FilterOption<ActivityPreset>[] =
	ACTIVITY_PRESETS.map((value) => ({
		value,
		label: PRESET_LABELS[value].label,
		shortLabel: PRESET_LABELS[value].shortLabel,
	}));

/** The URL's period: its days when both are valid and in order, else its preset. */
export function periodFromSearch(search: {
	range: ActivityPreset;
	from?: string;
	to?: string;
}): ActivityPeriod {
	const from = fromDayParam(search.from);
	const to = fromDayParam(search.to);
	if (from !== undefined && to !== undefined && from <= to) {
		return { kind: "custom", from, to };
	}
	return { kind: "preset", preset: search.range };
}

/** The search params that write a period; a custom period leaves the preset at its default. */
export function periodSearch(period: ActivityPeriod): {
	range: ActivityPreset | undefined;
	from: string | undefined;
	to: string | undefined;
} {
	return period.kind === "preset"
		? { range: period.preset, from: undefined, to: undefined }
		: { range: undefined, from: toDayParam(period.from), to: toDayParam(period.to) };
}

type PeriodQuery = Pick<NonNullable<GetActivityPeopleData["query"]>, "range" | "from" | "to">;

/**
 * The period as the server reads it. A preset is the server's, so it ends whenever the server reads
 * it and the query key stays the same while the clock moves.
 */
export function periodQuery(period: ActivityPeriod): PeriodQuery {
	return period.kind === "preset"
		? { range: period.preset }
		: { range: "custom", from: dayStartInstant(period.from), to: dayAfterInstant(period.to) };
}

/** "Last 90 days", "All time", "1–31 March 2026". */
export function periodLabel(period: ActivityPeriod): string {
	return period.kind === "preset"
		? PRESET_LABELS[period.preset].label
		: formatDayRange(period.from, period.to);
}

/**
 * The days a copy names, since a pasted "Last 90 days" ages: "12 July – 10 October 2026". A preset
 * ends `now` and starts its days before, as the server counts it. All of the history is "All time".
 */
export function periodDays(period: ActivityPeriod, now: Date): string {
	if (period.kind === "custom") {
		return formatDayRange(period.from, period.to);
	}
	return period.preset === "all"
		? PRESET_LABELS.all.label
		: formatDayRange(subDays(now, ACTIVITY_RANGE_DEFS[period.preset].days), now);
}

/**
 * The period of the same length just before the span the server counted, and its name after
 * "than": "the previous 30 days". All of the history has nothing before it.
 */
export function previousPeriod(
	period: ActivityPeriod,
	span: { from: Date; to: Date },
): { query: PeriodQuery; name: string } | undefined {
	const name =
		period.kind === "preset"
			? PRESET_LABELS[period.preset].previous
			: daysBefore(differenceInCalendarDays(period.to, period.from) + 1);
	if (name === undefined) {
		return undefined;
	}
	const length = span.to.getTime() - span.from.getTime();
	return {
		query: { range: "custom", from: new Date(span.from.getTime() - length), to: span.from },
		name,
	};
}

function daysBefore(days: number): string {
	return days === 1 ? "the day before" : `the previous ${days} days`;
}
