import type { ComponentType } from "react";

import { CircleDashed, type LucideIcon } from "lucide-react";

import { hasText } from "@/lib/text";

import { PracticeGroupIcon } from "./level-icons";

/**
 * Said under a table of colored group names: the color names the group and carries no rating. The
 * standings beside them have colors that carry meaning, so the group colors need saying apart.
 */
export const GROUP_COLORS_NOTE =
	"The colors of the group names only tell the groups apart. They are not a rating.";

export interface GroupVisual {
	Icon: LucideIcon;
	pill: string;
}

const SLATE_PILL = "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200";

/** Not in `PILL`: it marks the absence of a group, not a colour a group can pick. */
export const UNASSIGNED_GROUP_VISUAL: GroupVisual = {
	Icon: CircleDashed,
	pill: "bg-muted text-muted-foreground",
};

// Tailwind requires complete class names in source to include them in the generated CSS.
export const PILL: Record<string, string> = {
	red: "bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-200",
	orange: "bg-orange-100 text-orange-800 dark:bg-orange-950 dark:text-orange-200",
	amber: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200",
	yellow: "bg-yellow-100 text-yellow-800 dark:bg-yellow-950 dark:text-yellow-200",
	lime: "bg-lime-100 text-lime-800 dark:bg-lime-950 dark:text-lime-200",
	green: "bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-200",
	emerald: "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-200",
	teal: "bg-teal-100 text-teal-700 dark:bg-teal-950 dark:text-teal-200",
	cyan: "bg-cyan-100 text-cyan-800 dark:bg-cyan-950 dark:text-cyan-200",
	sky: "bg-sky-100 text-sky-700 dark:bg-sky-950 dark:text-sky-200",
	blue: "bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-200",
	indigo: "bg-indigo-100 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-200",
	violet: "bg-violet-100 text-violet-700 dark:bg-violet-950 dark:text-violet-200",
	purple: "bg-purple-100 text-purple-700 dark:bg-purple-950 dark:text-purple-200",
	fuchsia: "bg-fuchsia-100 text-fuchsia-700 dark:bg-fuchsia-950 dark:text-fuchsia-200",
	pink: "bg-pink-100 text-pink-700 dark:bg-pink-950 dark:text-pink-200",
	rose: "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-200",
	slate: SLATE_PILL,
	gray: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-200",
	zinc: "bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200",
	stone: "bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-200",
};

export const COLOR_KEYS = Object.keys(PILL);

function pillFor(color?: string | null): string | undefined {
	return hasText(color) ? PILL[color] : undefined;
}

export function pillClasses(color?: string | null): string {
	return pillFor(color) ?? SLATE_PILL;
}

export const DEFAULT_GROUP_COLOR = "slate";

/**
 * A group's color on its icon or name alone, without the pill's ground: the pill's own text
 * classes, so the color has one home in `PILL`.
 */
export function groupInk(pill: string): string {
	return pill
		.split(" ")
		.filter((name) => !/(?:^|:)bg-/u.test(name))
		.join(" ");
}

/** A group as a mark inside or beside another name: its icon, and its color without a ground. */
export interface GroupMark {
	Icon: ComponentType<{ className?: string }>;
	ink: string;
}

export function groupMark({
	Icon,
	pill,
}: {
	Icon: ComponentType<{ className?: string }>;
	pill: string;
}): GroupMark {
	return { Icon, ink: groupInk(pill) };
}

/** A group as the developer surfaces draw it: the one group icon, in the group's color. */
export function getGroupVisual(_icon?: string | null, color?: string | null): GroupVisual {
	const pill = pillFor(color) ?? SLATE_PILL;
	return { Icon: PracticeGroupIcon, pill };
}
