/** How far back a summary looks, always ending now. */
export const ACTIVITY_RANGES = ["30d", "90d", "1y"] as const;

export type ActivityRange = (typeof ACTIVITY_RANGES)[number];

interface ActivityRangeDef {
	days: number;
	/** "Last 30 days": the range as a control or a caption names it. */
	label: string;
	/** "30 days": the toggle row's chip, where width is the constraint. */
	shortLabel: string;
	/** "the last 30 days": the range inside a sentence, after "in". */
	inSentence: string;
	/** "the previous 30 days": the period of the same length before, which a figure is set against. */
	previous: string;
}

export const ACTIVITY_RANGE_DEFS = {
	"30d": {
		days: 30,
		label: "Last 30 days",
		shortLabel: "30 days",
		inSentence: "the last 30 days",
		previous: "the previous 30 days",
	},
	"90d": {
		days: 90,
		label: "Last 90 days",
		shortLabel: "90 days",
		inSentence: "the last 90 days",
		previous: "the previous 90 days",
	},
	"1y": {
		days: 365,
		label: "Last 12 months",
		shortLabel: "12 months",
		inSentence: "the last 12 months",
		previous: "the previous 12 months",
	},
} as const satisfies Record<ActivityRange, ActivityRangeDef>;
