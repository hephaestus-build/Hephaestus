import type { ReactNode } from "react";

import type { PracticeGroup, PracticeGroupStanding } from "@/api/types.gen";
import { type LoadState, loadProps } from "@/components/common/panel-state";
import type { DetailStackEntry } from "@/components/layout/detail-drawer/detail-stack";
import {
	type DetailDrawerLevel,
	DetailDrawerStack,
} from "@/components/layout/detail-drawer/DetailDrawerStack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import type {
	FeedbackRatingProps,
	PracticeFeedbackCardEntry,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { type PracticeGroupDetail, REVIEW_RUN_PAGE_SIZE } from "@/hooks/use-practice-group-detail";
import { hasText } from "@/lib/text";

import {
	DEFAULT_PRACTICE_TAB,
	openLevelId,
	type PracticeGroupDetailSelection,
	type PracticeProfileDetailLevelKind,
	type PracticeTab,
} from "./practice-profile-search";
import { PracticeDetailLevel } from "./PracticeDetailLevel";
import {
	PracticeGroupDetailLevel,
	type PracticeGroupDetailLevelProps,
} from "./PracticeGroupDetailLevel";

export type GroupLevelOverview = Pick<
	PracticeGroupDetailLevelProps,
	"holdingUp" | "holdingUpNote" | "reviewedWork" | "nextStep" | "practiceSentences"
>;

export interface PracticeGroupDetailDrawerProps {
	/**
	 * What the open group and practice levels show, fetched by the route with
	 * `usePracticeGroupDetail`.
	 */
	detail: PracticeGroupDetail;
	/**
	 * The parsed `detail` stack from the route's search params. The group level and, over it, the
	 * practice level are found by kind, so a route may stack them on a level of its own.
	 */
	detailStack: DetailStackEntry<PracticeProfileDetailLevelKind>[];
	/**
	 * Renders a level of a kind this drawer does not own; the route that added the kind supplies it.
	 */
	renderLevel?: (
		entry: DetailStackEntry<PracticeProfileDetailLevelKind>,
		level: DetailDrawerLevel,
		path: LevelPath,
	) => ReactNode;
	/** Names a level of a kind this drawer does not own, for the path of the levels over it. */
	levelLabel?: (entry: DetailStackEntry<PracticeProfileDetailLevelKind>) => string;
	/** Called with the depth to close down to; `useDetailStack(...).close`. */
	onClose: (depth: number) => void;
	/** Pushes the practice level over the group; `stackControls.open(practiceLevel(slug))`. */
	onOpenPractice: (practiceSlug: string) => void;
	/** The groups the levels name and the developer's standing in each, `usePracticeStandings`. */
	groups: PracticeGroup[];
	groupStandings: Record<string, PracticeGroupStanding | undefined>;
	/**
	 * The page's load state: a level shows nothing without the page's standings, so it waits and
	 * fails with them.
	 */
	state: LoadState;
	/** The developer's practice feedback, for the practice level's Feedback tab. */
	feedbackCards?: PracticeFeedbackCardEntry[];
	/**
	 * What Heph says on a group's level — its held rows, next step, reviewed work and practice
	 * sentences — composed by the route from the overview; a route without one shows the group
	 * without the card.
	 */
	groupOverview?: (groupSlug: string) => GroupLevelOverview;
	/**
	 * Wires one card's rating to wherever the ratings are kept; without it the cards cannot be rated.
	 */
	ratingProps?: (feedbackId: string) => FeedbackRatingProps;
	/** The practice level's tab, from the route's search params; the route owns the navigation. */
	practiceTab?: PracticeTab;
	/**
	 * How many rows the practice level's review-run skeleton stands in for. Defaults to the page
	 * size the route asks the feed for, `REVIEW_RUN_PAGE_SIZE`, so the skeleton is the size of what
	 * arrives.
	 */
	skeletonRows?: number;
	/**
	 * Writes the selection in place — the tab is a view of the level, not a place — so Escape and
	 * Back leave the level in one step however many were opened.
	 */
	onSelectionChange: (selection: PracticeGroupDetailSelection) => void;
}

/** The page under the stack, as the first crumb of every level's path. */
const PAGE_LABEL = "Practice profile";

/**
 * The detail drawer over the practice profile: a practice-group level and, over it, a practice
 * level, both addressed by the route's `detail` stack. The route's own levels come in through
 * `renderLevel`.
 */
export function PracticeGroupDetailDrawer({
	detail,
	detailStack,
	renderLevel,
	levelLabel,
	onClose,
	onOpenPractice,
	groups,
	groupStandings,
	state,
	feedbackCards,
	groupOverview,
	ratingProps,
	practiceTab = DEFAULT_PRACTICE_TAB,
	skeletonRows = REVIEW_RUN_PAGE_SIZE,
	onSelectionChange,
}: PracticeGroupDetailDrawerProps) {
	const groupIndex = detailStack.findIndex((entry) => entry.kind === "practice-group");
	const openGroupSlug = openLevelId(detailStack, "practice-group");
	const openPracticeSlug = openLevelId(detailStack, "practice");
	const openGroup = groups.find((candidate) => candidate.slug === openGroupSlug);
	const openStanding = hasText(openGroupSlug) ? groupStandings[openGroupSlug] : undefined;
	const load = loadProps(state);
	// A level behind is named by what it shows: a group by its name, a route's own level by the
	// route. The practice level is the deepest, so it is never behind another.
	const labelOf = (entry: DetailStackEntry<PracticeProfileDetailLevelKind>): string => {
		if (entry.kind === "practice-group") {
			return groups.find((candidate) => candidate.slug === entry.id)?.name ?? "Group";
		}
		if (entry.kind === "practice") {
			return "Practice";
		}
		return levelLabel?.(entry) ?? entry.kind;
	};
	const pathAt = levelPathAt(detailStack, { pageLabel: PAGE_LABEL, labelOf, onClose });

	return (
		<DetailDrawerStack stack={detailStack} size="detailWide" onClose={onClose}>
			{(entry, level): ReactNode => {
				if (entry.kind === "practice-group") {
					return (
						<PracticeGroupDetailLevel
							nested={level.nested}
							path={pathAt(level.depth)}
							group={openGroup}
							standing={openStanding}
							practices={detail.practices}
							{...(hasText(openGroupSlug) ? groupOverview?.(openGroupSlug) : undefined)}
							openPracticeSlug={openPracticeSlug}
							onOpenPractice={onOpenPractice}
							{...load}
						/>
					);
				}
				if (entry.kind === "practice") {
					return (
						<PracticeDetailLevel
							nested={level.nested}
							path={pathAt(level.depth)}
							practice={detail.practice}
							feed={detail.feed}
							feedbackCards={feedbackCards}
							ratingProps={ratingProps}
							skeletonRows={skeletonRows}
							// The group level is under the practice's whenever the group is known, so a
							// card's group name goes back to it.
							onOpenGroup={openGroup && (() => onClose(groupIndex + 1))}
							tab={practiceTab}
							onTabChange={(tab) =>
								// The default is the URL's silence.
								onSelectionChange({ practiceTab: tab === DEFAULT_PRACTICE_TAB ? undefined : tab })
							}
							observations={{
								onRespond: detail.respond,
								pendingFeedbackId: detail.pendingFeedbackId,
							}}
							{...load}
						/>
					);
				}
				return renderLevel?.(entry, level, pathAt(level.depth));
			}}
		</DetailDrawerStack>
	);
}
