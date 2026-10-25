import type { ReactNode } from "react";

import type { GroupRef, PracticeGroupStanding, ResultList } from "@/api/types.gen";
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
import type { ObservationFilter, SelectedObservationState } from "./observation-filter";
import { isNeverApplied } from "./practice-folds";
import { practicePatternOf } from "./practice-pattern";
import {
	ALL_PRACTICE_GROUPS,
	openLevelId,
	type PracticeGroupDetailSelection,
	type PracticeProfileDetailLevelKind,
	type PracticeTab,
	REVIEWS_OF_YOUR_WORK,
} from "./practice-profile-search";
import { PracticeDetailLevel } from "./PracticeDetailLevel";
import {
	PracticeGroupDetailLevel,
	type PracticeGroupDetailLevelProps,
} from "./PracticeGroupDetailLevel";
import { ProfileReviewLevel, type ProfileReviewLevelProps } from "./ProfileReviewLevel";
import { ProfileReviewsLevel, type ProfileReviewsLevelProps } from "./ProfileReviewsLevel";

export type GroupLevelOverview = Pick<PracticeGroupDetailLevelProps, "practiceSentences">;

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
	/** The groups the levels list and name, and the developer's standing in each. */
	groups: GroupRef[];
	/**
	 * The groups with no practice in them. Not in the table: they sit in the fold of groups not
	 * reviewed yet. A link to one opens its level, which says the group has no practices.
	 */
	emptyGroups?: GroupRef[];
	groupStandings: Record<string, PracticeGroupStanding | undefined>;
	/**
	 * The page's load state: a level shows nothing without the page's standings, so it waits and
	 * fails with them.
	 */
	state: LoadState;
	/** The developer's practice feedback, for the practice level's Feedback tab. */
	feedbackCards?: PracticeFeedbackCardEntry[];
	/**
	 * The sentence under each practice on a group's level; without it the rows show the practice
	 * alone.
	 */
	groupOverview?: (groupSlug: string) => GroupLevelOverview;
	/**
	 * Wires one card's rating to wherever the ratings are kept; without it the cards cannot be rated.
	 */
	ratingProps?: (feedbackId: string) => FeedbackRatingProps;
	/** The practice level's tab, from the route's search params; the route owns the navigation. */
	practiceTab: PracticeTab;
	/** The feedback card a link asked for on the practice level's Feedback tab. */
	markedFeedbackId?: string;
	renderObservation?: (result: ResultList) => ReactNode;
	/** The practice level's observation filter, from the route's search. */
	observationFilter?: ObservationFilter;
	/** The observation a link asked for, read on its own by the route. */
	selectedObservation?: SelectedObservationState;
	/** How many rows the practice level's feed draws while its first page loads. */
	skeletonRows: number;
	/** The two review levels; the drawer adds where each sits and which review is open. */
	reviews: {
		list: Omit<ProfileReviewsLevelProps, "nested" | "path" | "openReviewId">;
		open: Omit<ProfileReviewLevelProps, "nested" | "path" | "positionOnWork" | "totalOnWork">;
	};
	/**
	 * Writes the selection in place — the tab is a view of the level, not a place — so Escape and
	 * Back leave the level in one step however many were opened.
	 */
	onSelectionChange: (selection: PracticeGroupDetailSelection) => void;
	/** What a level shows under its body, such as the way to the same group across the workspace. */
	levelFooter?: (entry: DetailStackEntry<PracticeProfileDetailLevelKind>) => ReactNode;
}

/** The page under the stack, as the first crumb of every level's path. */
const PAGE_LABEL = "Practice profile";

const NO_GROUPS: GroupRef[] = [];

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
	emptyGroups = NO_GROUPS,
	groupStandings,
	state,
	feedbackCards,
	groupOverview,
	ratingProps,
	practiceTab,
	markedFeedbackId,
	renderObservation,
	observationFilter,
	selectedObservation,
	skeletonRows,
	reviews,
	onSelectionChange,
	levelFooter,
}: PracticeGroupDetailDrawerProps) {
	const groupIndex = detailStack.findIndex((entry) => entry.kind === "practice-group");
	const openGroupSlug = openLevelId(detailStack, "practice-group");
	const openPracticeSlug = openLevelId(detailStack, "practice");
	const findGroup = (slug: string | undefined) =>
		groups.find((candidate) => candidate.slug === slug) ??
		emptyGroups.find((candidate) => candidate.slug === slug);
	const openGroup = findGroup(openGroupSlug);
	const openStanding = hasText(openGroupSlug) ? groupStandings[openGroupSlug] : undefined;
	const load = loadProps(state);
	// Each practice of the group to the pattern of its open card.
	const patternsOf = (practices: readonly { slug: string }[]) =>
		Object.fromEntries(
			practices.map((practice) => [
				practice.slug,
				practicePatternOf(feedbackCards ?? [], practice.slug),
			]),
		);
	// A level behind is named by what it shows. The practice level is the deepest, so it is never
	// behind another.
	const labelOf = (entry: DetailStackEntry<PracticeProfileDetailLevelKind>): string => {
		switch (entry.kind) {
			case "practice-group": {
				return findGroup(entry.id)?.name ?? "Group";
			}
			case "practice": {
				return "Practice";
			}
			case "practice-groups": {
				return ALL_PRACTICE_GROUPS;
			}
			case "reviews": {
				return REVIEWS_OF_YOUR_WORK;
			}
			case "review": {
				return "Review";
			}
		}
	};
	const pathAt = levelPathAt(detailStack, { pageLabel: PAGE_LABEL, labelOf, onClose });
	const openReviewId = openLevelId(detailStack, "review");
	const { list, open } = reviews;
	// The open review's row in the list under it, which knows its place among the work's reviews.
	const openListed =
		list.feed.status === "ready"
			? list.feed.reviews.find((entry) => entry.review.id === openReviewId)
			: undefined;

	return (
		<DetailDrawerStack stack={detailStack} onClose={onClose} footer={levelFooter}>
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
								emptyGroups={emptyGroups}
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
								patterns={patternsOf(detail.practices)}
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
								described={detail.described}
								guidance={detail.guidance}
								feed={detail.feed}
								renderObservation={renderObservation}
								observationFilter={observationFilter}
								onObservationFilterChange={(filter) =>
									onSelectionChange({
										observation: filter.observationId,
										observationQuery: filter.query,
										observationOutcome: filter.outcome,
										observationSince: filter.since,
										observationWork: filter.workId,
									})
								}
								selectedObservation={selectedObservation}
								feedbackCards={feedbackCards}
								ratingProps={ratingProps}
								skeletonRows={skeletonRows}
								// The group level is under the practice's whenever the group is known, so a
								// card's group name goes back to it.
								onOpenGroup={openGroup && (() => onClose(groupIndex + 1))}
								tab={practiceTab}
								markedFeedbackId={markedFeedbackId}
								onFeedbackArrivalShown={() => onSelectionChange({ feedbackCard: undefined })}
								onTabChange={(tab) => onSelectionChange({ practiceTab: tab })}
								responses={detail.responses}
								neverApplied={detail.practice !== undefined && isNeverApplied(detail.practice)}
								{...load}
							/>
						);
					}
					case "reviews": {
						return (
							<ProfileReviewsLevel
								nested={level.nested}
								path={pathAt(level.depth)}
								{...list}
								openReviewId={openReviewId}
							/>
						);
					}
					case "review": {
						return (
							<ProfileReviewLevel
								nested={level.nested}
								path={pathAt(level.depth)}
								{...open}
								positionOnWork={openListed?.positionOnWork}
								totalOnWork={openListed?.totalOnWork}
								// The observation open over this review, from the address: after a reload the
								// row the reader came from is still marked once they go back.
								openedObservationId={
									openPracticeSlug === undefined ? undefined : observationFilter?.observationId
								}
							/>
						);
					}
				}
			}}
		</DetailDrawerStack>
	);
}
