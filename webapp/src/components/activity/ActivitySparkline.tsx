import type { ActivitySparklineWeek } from "@/api/types.gen";

import { weekStarts } from "./activity-tally";

export interface ActivitySparklineProps {
	/** The weeks with contributions; the server leaves out a week without any. */
	weeks: readonly ActivitySparklineWeek[];
	/** The span the weeks were counted in, which places the quiet weeks. */
	span: { from: Date; to: Date };
}

const WIDTH = 96;
const HEIGHT = 24;
/** Room for the stroke at the top and the bottom, so a peak or a zero is not cut in half. */
const INSET = 1.5;

/**
 * Contributions per week over the period, on the row's own scale from zero: the shape of a person's
 * work, never a comparison with the next row. A plain SVG, because a chart library per row of a
 * table of hundreds costs far more than the line it draws. Its words say the busiest week.
 */
export function ActivitySparkline({ weeks, span }: ActivitySparklineProps) {
	const counted = new Map(weeks.map((week) => [week.start.getTime(), week.contributions]));
	const values = weekStarts(span.from, span.to).map((start) => counted.get(start.getTime()) ?? 0);
	const peak = Math.max(0, ...values);
	const step = values.length > 1 ? (WIDTH - 2 * INSET) / (values.length - 1) : 0;
	const y = (value: number) =>
		HEIGHT - INSET - (peak === 0 ? 0 : value / peak) * (HEIGHT - 2 * INSET);
	const points = values.map((value, index) => `${INSET + index * step},${y(value)}`);
	const line =
		points.length > 1
			? points.join(" ")
			: `${INSET},${y(values[0] ?? 0)} ${WIDTH - INSET},${y(values[0] ?? 0)}`;
	return (
		<svg
			role="img"
			aria-label={
				peak === 0
					? "No contributions"
					: `Busiest week: ${peak} ${peak === 1 ? "contribution" : "contributions"}`
			}
			viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
			width={WIDTH}
			height={HEIGHT}
			className="overflow-visible text-muted-foreground"
		>
			<polyline
				points={line}
				fill="none"
				stroke="currentColor"
				strokeWidth={1.5}
				strokeLinejoin="round"
				strokeLinecap="round"
			/>
		</svg>
	);
}
