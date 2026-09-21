import { cva, type VariantProps } from "class-variance-authority";

import type { PracticeStanding } from "@/api/types.gen";
import { cn } from "@/lib/utils";

import { PRACTICE_GROUP_STANDING_DEFS } from "./practice-group-standing-defs";
import { statusToneClass, statusValues } from "./status-def";

type Standing = PracticeStanding["standing"];
/**
 * Only the standing is read, so a practice the detail levels shape counts the same as a wire one.
 */
type HasStanding = Pick<PracticeStanding, "standing">;
/**
 * The two standings no review has settled draw in the legend's greys rather than a verdict's
 * colour.
 */
const RING_OPACITY: Partial<Record<Standing, string>> = {
	NOT_OBSERVED: "text-muted-foreground/75",
	NO_OPPORTUNITY: "text-muted-foreground/45",
};

const SEGMENTS: ReadonlyArray<{
	standing: Standing;
	colorClass: string;
	label: string;
}> = statusValues(PRACTICE_GROUP_STANDING_DEFS).map((standing) => ({
	standing,
	colorClass:
		RING_OPACITY[standing] ?? statusToneClass(PRACTICE_GROUP_STANDING_DEFS[standing].badgeVariant),
	label: PRACTICE_GROUP_STANDING_DEFS[standing].shortLabel,
}));

export const STANDING_LEGEND = SEGMENTS;

export type StandingCounts = Partial<Record<Standing, number>>;

export function countPracticeStandings(practices: ReadonlyArray<HasStanding>): StandingCounts {
	const counts: StandingCounts = {};
	for (const practice of practices) {
		counts[practice.standing] = (counts[practice.standing] ?? 0) + 1;
	}
	return counts;
}

/**
 * Every standing with at least one practice, in registry order, with its colour and short label.
 */
export function summarizeStandingCounts(counts: StandingCounts) {
	return SEGMENTS.map((segment) => ({ ...segment, count: counts[segment.standing] ?? 0 })).filter(
		(segment) => segment.count > 0,
	);
}

const standingCountsListVariants = cva("", {
	variants: {
		size: {
			/** A table cell beside the small ring. */
			sm: "text-xs [&_svg]:size-3.5",
			/** The summary box beside the large ring. */
			md: "text-sm [&_svg]:size-4",
		},
	},
	defaultVariants: { size: "md" },
});

export interface StandingCountsListProps extends VariantProps<typeof standingCountsListVariants> {
	counts: StandingCounts;
	/** The list's accessible name: what the counts are of. */
	"aria-label": string;
	/** The items' layout; a column unless the caller lays them out otherwise. */
	className?: string;
}

/**
 * The legend beside a ring: one line per standing present, in the ring's own order, with the
 * count and the registry's short label. Plain text, so it carries no tooltip; the registry's
 * sentence stays where a standing is a badge. With nothing counted there is no list, since an
 * empty legend would claim a count of nothing.
 */
export function StandingCountsList({ counts, size, className, ...props }: StandingCountsListProps) {
	const segments = summarizeStandingCounts(counts);
	if (segments.length === 0) return null;
	return (
		<ul
			className={cn(
				"flex flex-col gap-px text-muted-foreground",
				standingCountsListVariants({ size }),
				className,
			)}
			{...props}
		>
			{segments.map(({ standing, count, colorClass, label }) => {
				const StandingIcon = PRACTICE_GROUP_STANDING_DEFS[standing].icon;
				return (
					<li key={standing} className="whitespace-nowrap inline-flex items-center gap-1.5">
						<StandingIcon className={cn("shrink-0", colorClass)} aria-hidden />
						<span className="font-semibold text-foreground tabular-nums">{count}</span>
						{label}
					</li>
				);
			})}
		</ul>
	);
}

/** The ring is drawn on a 100-unit circumference, so a share in percent is a dash length. */
const RADIUS = 100 / (2 * Math.PI);
const CENTER = 18;

/**
 * The ring in its two sizes: large beside a summary box's counts, in the page header and a
 * group's header; small in a table cell, where the stroke is thicker so the arcs still read.
 */
const RING_SIZES = {
	lg: { diameter: 60, strokeWidth: 5 },
	sm: { diameter: 44, strokeWidth: 5.5 },
} as const;

export type PracticeGroupStandingRingSize = keyof typeof RING_SIZES;

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
