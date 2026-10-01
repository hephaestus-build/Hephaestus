import type { ReactElement, ReactNode } from "react";

import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";

import { PRACTICE_REVIEW_LEVEL_LABELS, type PracticeReviewLevelKind } from "./review-levels";

export interface ReviewLevelHeaderProps {
	nested?: boolean;
	path: LevelPath;
	kind: PracticeReviewLevelKind;
	/** The record's standing, under the title. */
	chips?: ReactElement | undefined;
	/** The record's name; without one — a record that failed to load — the level's kind names it. */
	title?: ReactNode;
	/** The record is on its way: its name's shape stands in, and the drawer is named by what loads. */
	loading?: boolean;
	/** One line under the standing: the work, the time, or the review it came from. */
	description?: ReactNode;
}

/** A Practice reviews level's {@link LevelHeader}, named by its kind. */
export function ReviewLevelHeader({ kind, ...props }: ReviewLevelHeaderProps) {
	return <LevelHeader {...props} current={PRACTICE_REVIEW_LEVEL_LABELS[kind]} />;
}
