import type { ReactNode } from "react";

import type { PracticeGroup, PracticeGroupStanding } from "@/api/types.gen";
import { type LoadState, loadProps } from "@/components/common/panel-state";
import type { DetailStackEntry } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import { sortPracticeGroups } from "@/components/practice-vocabulary/practice-group-list-order";
import type {
	FeedbackRatingProps,
	PracticeFeedbackCardEntry,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import type { PracticeGroupDetail } from "@/hooks/use-practice-group-detail";
import { hasText } from "@/lib/text";

import { AllPracticeGroupsLevel } from "./AllPracticeGroupsLevel";
import type { AllPracticeGroupsTableProps } from "./AllPracticeGroupsTable";
import {
	ALL_PRACTICE_GROUPS,
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
	/** What the open group and practice levels show. */
	detail: PracticeGroupDetail;
	/**
	 * The parsed `detail` stack from the route's search params. The group level and, over it, the
	 * practice level are found by kind, so either may sit on the "All practice groups" level.
	 */
	detailStack: DetailStackEntry<PracticeProfileDetailLevelKind>[];
	/**
	 * The "All practice groups" level's table; the drawer orders its groups under `sort` and marks
	 * the one open over it.
	 */
	allPracticeGroups: Pick<
		AllPracticeGroupsTableProps,
		"practicesByGroup" | "sentences" | "sort" | "onSortChange" | "onOpenGroup" | "onOpenPractice"
	>;
	/** Called with the depth to close down to. */
	onClose: (depth: number) => void;
	/** Pushes the practice level over the group. */
	onOpenPractice: (practiceSlug: string) => void;
	/** The groups the levels name and the developer's standing in each. */
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
	 * sentences; without it the group shows without the card.
	 */
	groupOverview?: (groupSlug: string) => GroupLevelOverview;
	/**
	 * Wires one card's rating to wherever the ratings are kept; without it the cards cannot be rated.
	 */
	ratingProps?: (feedbackId: string) => FeedbackRatingProps;
	/** The practice level's tab, from the route's search params; the route owns the navigation. */
	practiceTab: PracticeTab;
	/** How many rows the practice level's feed draws while its first page loads. */
	skeletonRows: number;
	/**
	 * Writes the selection in place — the tab is a view of the level, not a place — so Escape and
	 * Back leave the level in one step however many were opened.
	 */
	onSelectionChange: (selection: PracticeGroupDetailSelection) => void;
}

/** The page under the stack, as the first crumb of every level's path. */
const PAGE_LABEL = "Practice profile";

/**
 * The detail drawer over the practice profile, addressed by the route's `detail` stack: the
 * "All practice groups" level, a practice-group level and, over it, a practice level.
 */
export function PracticeGroupDetailDrawer({
	detail,
	detailStack,
	allPracticeGroups,
	onClose,
	onOpenPractice,
	groups,
	groupStandings,
	state,
	feedbackCards,
	groupOverview,
	ratingProps,
	practiceTab,
	skeletonRows,
	onSelectionChange,
}: PracticeGroupDetailDrawerProps) {
	const groupIndex = detailStack.findIndex((entry) => entry.kind === "practice-group");
	const openGroupSlug = openLevelId(detailStack, "practice-group");
	const openPracticeSlug = openLevelId(detailStack, "practice");
	const openGroup = groups.find((candidate) => candidate.slug === openGroupSlug);
	const openStanding = hasText(openGroupSlug) ? groupStandings[openGroupSlug] : undefined;
	const load = loadProps(state);
	// A level behind is named by what it shows. The practice level is the deepest, so it is never
	// behind another.
	const labelOf = (entry: DetailStackEntry<PracticeProfileDetailLevelKind>): string => {
		switch (entry.kind) {
			case "practice-group": {
				return groups.find((candidate) => candidate.slug === entry.id)?.name ?? "Group";
			}
			case "practice": {
				return "Practice";
			}
			case "practice-groups": {
				return ALL_PRACTICE_GROUPS;
			}
		}
	};
	const pathAt = levelPathAt(detailStack, { pageLabel: PAGE_LABEL, labelOf, onClose });

	return (
		<DetailDrawerStack stack={detailStack} size="detailWide" onClose={onClose}>
			{(entry, level): ReactNode => {
				switch (entry.kind) {
					case "practice-groups": {
						return (
							<AllPracticeGroupsLevel
								nested={level.nested}
								path={pathAt(level.depth)}
								{...allPracticeGroups}
								groups={sortPracticeGroups(groups, groupStandings, allPracticeGroups.sort)}
								standings={groupStandings}
								openGroupSlug={openGroupSlug}
								state={state}
							/>
						);
					}
					case "practice-group": {
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
					case "practice": {
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
								onTabChange={(tab) => onSelectionChange({ practiceTab: tab })}
								observations={{
									onRespond: detail.respond,
									pendingResponses: detail.pendingResponses,
								}}
								{...load}
							/>
						);
					}
				}
			}}
		</DetailDrawerStack>
	);
}
