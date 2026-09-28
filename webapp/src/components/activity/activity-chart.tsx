import type { ReactNode } from "react";
import { Rectangle } from "recharts";

/**
 * The marks and chrome every activity chart shares, so a tile's bars and a category's rows read
 * as one family: a column is at most 24px wide with a 4px rounded data end and a square foot, and
 * text never wears a series colour.
 */
export const BAR_RADIUS: [number, number, number, number] = [4, 4, 0, 0];

/** The widest a column gets: past it, the band's leftover is air, not ink. */
export const MAX_BAR_SIZE = 24;

/**
 * The faint full-height track behind every bucket's column, so an empty day reads as present and
 * zero, and the grid of days shows.
 */
export const TRACK_FILL = "var(--color-muted)";

interface BarTooltipProps {
	/** Injected by Recharts. */
	active?: boolean;
	/** Injected by Recharts: the hovered bucket's values. */
	payload?: readonly { value?: unknown }[];
	/** Injected by Recharts: the hovered bucket's start, in milliseconds. */
	label?: unknown;
	/** What the number counts: "Merged", "Approved". */
	name: string;
	/** The bucket as the tooltip names it. */
	bucketLabel: (start: Date) => string;
}

/**
 * One bar's tooltip, value first — the reader already knows which series they pointed at and wants
 * the number — then what it counts and when.
 */
export function BarTooltip({ active, payload, label, name, bucketLabel }: BarTooltipProps) {
	const value = payload?.[0]?.value;
	if (active !== true || typeof value !== "number" || typeof label !== "number") {
		return null;
	}
	return (
		<div className="grid gap-0.5 rounded-lg border bg-background px-2.5 py-1.5 text-xs shadow-xl">
			<span className="text-sm font-semibold text-foreground tabular-nums">{value}</span>
			<span className="text-muted-foreground">
				{name} · {bucketLabel(new Date(label))}
			</span>
		</div>
	);
}

interface PeakLabelProps {
	/** Injected by Recharts: the bar the label belongs to. */
	viewBox?: { x?: number; y?: number; width?: number };
	/** Injected by Recharts: the value, left out for every bar but the peak. */
	value?: unknown;
}

/** The peak's value on its cap, in the text colour; every other bar is given no value to write. */
export function PeakLabel({ viewBox, value }: PeakLabelProps): ReactNode {
	if (typeof value !== "number" || viewBox === undefined) {
		return null;
	}
	const { x = 0, y = 0, width = 0 } = viewBox;
	return (
		<text
			x={x + width / 2}
			y={y - 4}
			textAnchor="middle"
			className="fill-foreground text-xs font-medium tabular-nums"
		>
			{value}
		</text>
	);
}

/** The value a bar's label writes: its count on the peak bucket, nothing on any other. */
export function peakValue(
	rows: readonly { start: number; count: number }[],
): (entry: { payload?: unknown }) => number | undefined {
	const peak = peakIndex(rows.map((row) => row.count));
	const at = peak === undefined ? undefined : rows[peak];
	return (entry) => {
		const payload: unknown = entry.payload;
		const start: unknown =
			typeof payload === "object" && payload !== null ? Reflect.get(payload, "start") : undefined;
		return at !== undefined && start === at.start ? at.count : undefined;
	};
}

/** The first bucket with the most, or none when nothing happened: where the peak label goes. */
export function peakIndex(values: readonly number[]): number | undefined {
	const peak = Math.max(0, ...values);
	return peak === 0 ? undefined : values.indexOf(peak);
}

interface CountShapeProps {
	/** Injected by Recharts: the bar's box and paint, and the row it draws. */
	x?: number;
	y?: number;
	width?: number;
	height?: number;
	fill?: string;
	payload?: unknown;
}

/**
 * A count's column in its track: its data end rounded where it is the top of the track, square
 * where the rest of the track sits on it, so the two read as one column without a notch between.
 */
export function CountShape({ x, y, width, height, fill, payload }: CountShapeProps) {
	const rest: unknown =
		typeof payload === "object" && payload !== null ? Reflect.get(payload, "rest") : 0;
	return (
		<Rectangle
			x={x}
			y={y}
			width={width}
			height={height}
			fill={fill}
			radius={rest === 0 ? BAR_RADIUS : 0}
		/>
	);
}
