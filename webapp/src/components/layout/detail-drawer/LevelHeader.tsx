import type { ReactElement, ReactNode } from "react";

import { cn } from "cn";
import { DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";
import { rendersContent } from "@/lib/react-node";

import { DetailDrawerHeader } from "./DetailDrawerHeader";
import { DetailPath, type LevelPath } from "./DetailPath";

export interface LevelHeaderProps {
	/** True below the top level, where dismissing returns to the drawer behind. */
	nested?: boolean;
	/** Where the level sits, from the host's `levelPathAt`. */
	path: LevelPath;
	/**
	 * What this level is, as the last crumb of its path names it: "Group", "Review". A level that is
	 * its own kind — a member, an activity category, the practice editor — is named by its title.
	 */
	current: string;
	/** The record's name; a level named by what it is leaves it out and is titled by `current`. */
	title?: ReactNode;
	/** A picture of what the record is, leading the title: a group's pill, a member's avatar. */
	mark?: ReactElement;
	/** The record's standing — badges, a trend — under the title, where text-like status goes. */
	chips?: ReactNode;
	/**
	 * One line under the standing: the work, the time, where the record came from, or what the level
	 * is for. It describes the drawer, so it stays under the title even beside a `mark`.
	 */
	description?: ReactNode;
	/**
	 * A summary that is a picture rather than text, beside the title from `sm` and under it below —
	 * the one second column a header allows.
	 */
	aside?: ReactElement;
	/** The record is on its way: its name's shape stands in, and the drawer is named by what loads. */
	loading?: boolean;
}

/**
 * The header of every level a host names with `levelPathAt`: its path, its name, its standing and
 * one line of provenance, in one column so it holds at 320px (`webapp/AGENTS.md` § Panel regions).
 * A level's actions are its footer, never a column beside the title.
 */
export function LevelHeader({
	nested,
	path,
	current,
	title = current,
	mark,
	chips,
	description,
	aside,
	loading = false,
}: LevelHeaderProps) {
	return (
		<DetailDrawerHeader nested={nested}>
			<div className={cn("flex min-w-0 flex-1 flex-col gap-2", aside && "basis-56")}>
				<DetailPath {...path} current={current} />
				<div className="flex min-w-0 items-center gap-3">
					{mark}
					{/* A drawer is named by its title, so the heading stays while the record loads; out of
					    the row, so the skeleton standing in for it sits where the name will. */}
					<DrawerTitle
						className={cn(
							"min-w-0 text-2xl font-semibold tracking-tight break-words",
							loading && "sr-only",
						)}
					>
						{loading ? `Loading ${current.toLowerCase()}` : title}
					</DrawerTitle>
					{loading && <Skeleton aria-hidden className="h-7 w-full max-w-md" />}
				</div>
				{/* `empty:hidden`: a badge that renders nothing for its value leaves no gap behind. */}
				{rendersContent(chips) && (
					<div className="flex flex-wrap items-center gap-x-3 gap-y-2 empty:hidden">{chips}</div>
				)}
				{rendersContent(description) && (
					<DrawerDescription render={<div />}>
						<div className="flex flex-col gap-1">{description}</div>
					</DrawerDescription>
				)}
			</div>
			{aside && <div className="w-full sm:w-auto">{aside}</div>}
		</DetailDrawerHeader>
	);
}
