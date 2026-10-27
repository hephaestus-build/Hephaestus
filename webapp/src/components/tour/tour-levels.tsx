import type { ResultList } from "@/api/types.gen";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import {
	FOCUS_FOOTER_COPY,
	PracticeFocusFooter,
} from "@/components/practice-focus/PracticeFocusFooter";
import type { PracticeGuidanceState } from "@/components/practice-guidance/PracticeIntro";
import { AllPracticeGroupsLevel } from "@/components/practice-profile/AllPracticeGroupsLevel";
import {
	composeGroupOverview,
	composeOverview,
} from "@/components/practice-profile/compose-overview";
import type { PracticeTab } from "@/components/practice-profile/practice-profile-search";
import { PracticeDetailLevel } from "@/components/practice-profile/PracticeDetailLevel";
import { PracticeGroupDetailLevel } from "@/components/practice-profile/PracticeGroupDetailLevel";
import { ProfileReviewsLevel } from "@/components/practice-profile/ProfileReviewsLevel";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortPracticeGroups,
} from "@/components/practice-vocabulary/practice-group-list-order";
import { WorkspaceGroupLevel } from "@/components/practices-across-the-workspace/WorkspaceGroupLevel";
import { ReviewResultDetails } from "@/components/profile/ReviewResultRow";
import type { ReviewFeedState } from "@/components/profile/reviews";
import { useFeedbackRatings } from "@/stories/feedback-ratings";
import {
	detailPractices,
	detailResult,
	detailReviews,
	focusedChanges,
} from "@/stories/practice-detail-story-mock-data";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import { bundledGuidance } from "@/stories/practice-guidance-story-mock-data";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	packagingGroup,
	packagingStanding,
	practicesByGroup,
} from "@/stories/practice-profile-story-mock-data";
import { PACKAGING_GROUP } from "@/stories/practices-across-the-workspace-story-data";
import { profileReviews } from "@/stories/profile-reviews-story-mock-data";

/** The levels the tour opens over its example profile, outermost first. */
export type TourLevel = "groups" | "group" | "practice" | "reviews";

/** The example levels' handlers: the tour drives the levels, the reader does not act on them. */
const noop = () => undefined;

const readyFeed = {
	status: "ready",
	reviews: detailReviews,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: noop,
} satisfies ReviewFeedState;

const reviewsFeed = {
	status: "ready",
	reviews: profileReviews,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: noop,
} satisfies ReviewFeedState;

const readyGuidance = { status: "ready", ...bundledGuidance } satisfies PracticeGuidanceState;

const completeDetail = (result: ResultList) =>
	result.id === detailResult.id ? <ReviewResultDetails result={detailResult} /> : undefined;

const PROFILE = { label: "Practice profile", depth: 0 };
const ALL = { label: "All practice groups", depth: 1 };
const GROUP = { label: packagingGroup.name, depth: 2 };

const ENTRY = {
	groups: { kind: "practice-groups", id: "all" },
	group: { kind: "practice-group", id: packagingGroup.slug },
	practice: { kind: "practice", id: focusedChanges.slug },
	reviews: { kind: "reviews", id: "all" },
} as const;

/** The way across at the foot of each level that has one, as the app shows it. */
const FOOTER_COPY: Partial<
	Record<
		(typeof ENTRY)[TourLevel]["kind"],
		(typeof FOCUS_FOOTER_COPY)[keyof typeof FOCUS_FOOTER_COPY]
	>
> = {
	"practice-groups": FOCUS_FOOTER_COPY.profileAllGroups,
	"practice-group": FOCUS_FOOTER_COPY.profileGroup,
	practice: FOCUS_FOOTER_COPY.profilePractice,
};

/**
 * The app's own drawer levels over the tour's example profile, with story data: they slide in and
 * out as the app's do when the tour adds or removes a level.
 */
export function TourLevels({
	levels,
	practiceTab,
}: {
	levels: TourLevel[];
	practiceTab: PracticeTab;
}) {
	const { ratingProps } = useFeedbackRatings();
	const stack = levels.map((level) => ENTRY[level]);
	return (
		<DetailDrawerStack
			stack={stack}
			onClose={noop}
			modal={false}
			// The way across, at the foot of a level, as the app shows it.
			footer={(entry) => {
				const copy = FOOTER_COPY[entry.kind];
				if (copy === undefined) {
					return null;
				}
				return <PracticeFocusFooter {...copy} accessibleLabel={copy.label} onSwitch={noop} />;
			}}
		>
			{(entry, level) => {
				switch (entry.kind) {
					case "practice-groups": {
						return (
							<AllPracticeGroupsLevel
								nested={level.nested}
								path={{ behind: [PROFILE], onClose: noop }}
								groups={sortPracticeGroups(groups, groupStandings, DEFAULT_PRACTICE_GROUP_SORT)}
								standings={groupStandings}
								practicesByGroup={practicesByGroup}
								sentences={composeOverview(OVERVIEW_FIXTURE).groupSentences}
								sort={DEFAULT_PRACTICE_GROUP_SORT}
								onSortChange={noop}
								onOpenGroup={noop}
								onOpenPractice={noop}
								state={{ status: "ready" }}
							/>
						);
					}
					case "practice-group": {
						return (
							<PracticeGroupDetailLevel
								nested={level.nested}
								path={{
									behind: levels[0] === "groups" ? [PROFILE, ALL] : [PROFILE],
									onClose: noop,
								}}
								group={packagingGroup}
								standing={packagingStanding}
								practices={detailPractices}
								{...composeGroupOverview(OVERVIEW_FIXTURE, packagingGroup.slug)}
								onOpenPractice={noop}
								isLoading={false}
								onRetry={noop}
							/>
						);
					}
					case "practice": {
						return (
							<PracticeDetailLevel
								nested={level.nested}
								path={{ behind: [PROFILE, ALL, GROUP], onClose: noop }}
								practice={focusedChanges}
								guidance={readyGuidance}
								feed={readyFeed}
								feedbackCards={ALL_FEEDBACK_CARDS}
								group={packagingGroup}
								onOpenGroup={noop}
								tab={practiceTab}
								onTabChange={noop}
								skeletonRows={3}
								isLoading={false}
								onRetry={noop}
								renderObservation={completeDetail}
								ratingProps={ratingProps}
							/>
						);
					}
					case "reviews": {
						return (
							<ProfileReviewsLevel
								nested={level.nested}
								path={{ behind: [PROFILE], onClose: noop }}
								feed={reviewsFeed}
								onOpenReview={noop}
								onKindChange={noop}
								onSinceChange={noop}
								onStatusChange={noop}
								onOrderChange={noop}
								onTrack={noop}
								total={profileReviews.length}
								onReviewNow={noop}
							/>
						);
					}
				}
			}}
		</DetailDrawerStack>
	);
}

/** One group over the example Practices across the workspace, with the way back to the profile at its foot. */
export function TourAcrossLevel({ open }: { open: boolean }) {
	return (
		<DetailDrawerStack
			stack={open ? [{ kind: "workspace-group", id: "review-ready-work" }] : []}
			onClose={noop}
			modal={false}
			footer={() => (
				<PracticeFocusFooter
					{...FOCUS_FOOTER_COPY.workspaceGroup}
					accessibleLabel={FOCUS_FOOTER_COPY.workspaceGroup.label}
					onSwitch={noop}
				/>
			)}
		>
			{(_entry, level) => (
				<WorkspaceGroupLevel
					nested={level.nested}
					path={{ behind: [{ label: "Practices across the workspace", depth: 0 }], onClose: noop }}
					state={{ status: "ready", group: PACKAGING_GROUP }}
					focusPracticeSlug="scope-one-reviewable-change"
					onGoToPractice={noop}
				/>
			)}
		</DetailDrawerStack>
	);
}
