import type { ReactElement, ReactNode } from "react";

import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";
import { rendersContent } from "@/lib/react-node";

import { PRACTICE_REVIEW_LEVEL_LABELS, type PracticeReviewLevelKind } from "./review-levels";

export interface ReviewLevelHeaderProps {
	nested?: boolean;
	path: LevelPath;
	kind: PracticeReviewLevelKind;
	/** The record's standing, above the title: it says what the reader is about to read. */
	chips?: ReactElement | undefined;
	/** The record's name; without one — a record that failed to load — the level's kind names it. */
	title?: ReactNode;
	/** The record is on its way: its name's shape stands in, and the drawer is named by what loads. */
	loading?: boolean;
	/** One line under the title: the work, the time, or the review it came from. */
	description?: ReactNode;
}

/**
 * The header every Practice reviews level shares: where the level sits, its standing, its name and
 * one line of provenance, in one column so it holds at 320px (`webapp/AGENTS.md` § Panel regions).
 * A level's actions are its footer, never a column beside the title.
 */
export function ReviewLevelHeader({
	nested,
	path,
	kind,
	chips,
	title,
	loading = false,
	description,
}: ReviewLevelHeaderProps) {
	const label = PRACTICE_REVIEW_LEVEL_LABELS[kind];
	return (
		<DetailDrawerHeader nested={nested}>
			<div className="flex min-w-0 flex-1 flex-col gap-2">
				<DetailPath {...path} current={label} />
				{chips !== undefined && <div className="flex flex-wrap items-center gap-2">{chips}</div>}
				{/* A drawer is named by its title, so the heading stays while the record loads. */}
				<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
					{loading ? (
						<span className="sr-only">Loading {label.toLowerCase()}</span>
					) : (
						(title ?? label)
					)}
				</DrawerTitle>
				{loading && <Skeleton aria-hidden className="h-7 w-full max-w-md" />}
				{rendersContent(description) && (
					<DrawerDescription render={<div />}>
						<div className="flex flex-col gap-1">{description}</div>
					</DrawerDescription>
				)}
			</div>
		</DetailDrawerHeader>
	);
}
