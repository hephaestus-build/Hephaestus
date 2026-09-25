import { cn } from "cn";

import { type StandingCounts, summarizeStandingCounts } from "./standing-counts";

/** The ring is drawn on a 100-unit circumference, so a share in percent is a dash length. */
const RADIUS = 100 / (2 * Math.PI);
const CENTER = 18;

/**
 * The ring in its two sizes: large beside a summary box's counts, small in a table cell, where the
 * stroke is thicker so the arcs still read.
 */
const RING_SIZES = {
	lg: { diameter: 60, strokeWidth: 5 },
	sm: { diameter: 44, strokeWidth: 5.5 },
} as const;

type PracticeGroupStandingRingSize = keyof typeof RING_SIZES;

export interface PracticeGroupStandingRingProps {
	/**
	 * How many practices sit at each standing, `countPracticeStandings`; the legend beside it
	 * reads the same counts.
	 */
	counts: StandingCounts;
	/** `lg` beside a summary box, `sm` in a table cell. */
	size?: PracticeGroupStandingRingSize;
	className?: string;
}

/**
 * One arc per standing present, in registry order — what needs attention first — with a 3-unit
 * gap between neighbours. Decorative: the same counts stand beside it as text, so it is
 * `aria-hidden`. With nothing counted the track is a single hairline circle.
 */
export function PracticeGroupStandingRing({
	counts,
	size = "lg",
	className,
}: PracticeGroupStandingRingProps) {
	const { diameter, strokeWidth } = RING_SIZES[size];
	const segments = summarizeStandingCounts(counts);
	const total = segments.reduce((sum, segment) => sum + segment.count, 0);
	const gap = segments.length > 1 ? 3 : 0;
	let cursor = 0;

	return (
		<svg
			viewBox="-3 -3 42 42"
			width={diameter}
			height={diameter}
			className={cn("shrink-0 -rotate-90", className)}
			aria-hidden
		>
			{total === 0 ? (
				<circle
					cx={CENTER}
					cy={CENTER}
					r={RADIUS}
					fill="none"
					strokeWidth={1}
					className="stroke-current text-border"
				/>
			) : (
				segments.map((segment) => {
					const share = (segment.count / total) * 100;
					const dash = Math.max(share - gap, 0.5);
					const start = cursor + (share - dash) / 2;
					cursor += share;
					return (
						<circle
							key={segment.standing}
							cx={CENTER}
							cy={CENTER}
							r={RADIUS}
							fill="none"
							strokeWidth={strokeWidth}
							strokeDasharray={`${dash} ${100 - dash}`}
							strokeDashoffset={-start}
							className={cn("stroke-current", segment.colorClass)}
						/>
					);
				})
			)}
		</svg>
	);
}
