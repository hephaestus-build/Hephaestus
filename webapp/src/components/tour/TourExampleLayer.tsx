import { useLayoutEffect, useState } from "react";
import { createPortal } from "react-dom";

import { cn } from "cn";
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
import { PracticeProfilePage } from "@/components/practice-profile/PracticeProfilePage";
import { ProfileReviewsLevel } from "@/components/practice-profile/ProfileReviewsLevel";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortPracticeGroups,
} from "@/components/practice-vocabulary/practice-group-list-order";
import type { FeedbackRatingProps } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { PracticesAcrossTheWorkspacePage } from "@/components/practices-across-the-workspace/PracticesAcrossTheWorkspacePage";
import { WorkspaceGroupLevel } from "@/components/practices-across-the-workspace/WorkspaceGroupLevel";
import type { ReviewFeedState } from "@/components/profile/reviews";

import type { ExampleProfile } from "./example-profile";

/** The levels the tour opens over its example profile, outermost first. */
export type TourLevel = "groups" | "group" | "practice" | "reviews";

/** What the example under a step shows: which page, and which levels are open over it. */
export type ExampleView =
	| { page: "profile"; levels?: readonly TourLevel[]; practiceTab?: PracticeTab }
	| { page: "across"; level?: boolean };

/** The caption over every example, and the words a step says about it. */
export const EXAMPLE_CAPTION = "Example, not your work";

/** The example's handlers never run: it is inert. They make the components draw their controls. */
const noop = () => undefined;

const exampleRating = (): FeedbackRatingProps => ({
	onRate: noop,
	onDisagree: noop,
	onResolve: noop,
});

interface Inset {
	top: number;
	left: number;
}

/** The app's own content area, under its top bar and beside its sidebar; the whole window without one. */
function contentInset(): Inset | null {
	const main =
		document.querySelector('[data-slot="sidebar-inset"] > main') ??
		document.querySelector('[data-slot="sidebar-inset"]');
	if (main === null) {
		return null;
	}
	const area = main.getBoundingClientRect();
	return { top: area.top, left: area.left };
}

export interface TourExampleLayerProps {
	view: ExampleView;
	example: ExampleProfile;
	/** Receives the example's page, where a step finds the part it points at. */
	pageRef: (element: HTMLDivElement | null) => void;
}

/**
 * The example in place of the reader's own page, beside the app's sidebar. The tour draws its caption
 * above everything, so it stays in sight while levels cover the page.
 * It sits under the app's drawers, so the levels it opens slide over it as they do in the app; the
 * tour's dimming and its guide sit over both. Nothing here can be reached: the page and its levels
 * are inert, and the tour's words say what they show.
 */
export function TourExampleLayer({ view, example, pageRef }: TourExampleLayerProps) {
	const [inset, setInset] = useState<Inset | null>(null);
	useLayoutEffect(() => {
		const update = () => setInset(contentInset());
		update();
		window.addEventListener("resize", update);
		return () => window.removeEventListener("resize", update);
	}, []);
	const levelsOpen = view.page === "profile" ? (view.levels?.length ?? 0) > 0 : view.level === true;

	return createPortal(
		<div
			data-tour-example=""
			className={cn(
				"fixed z-40 flex flex-col bg-background",
				inset === null ? "inset-0" : "top-(--tour-top) right-0 bottom-0 left-(--tour-left)",
			)}
			style={
				inset === null
					? undefined
					: { "--tour-top": `${inset.top}px`, "--tour-left": `${inset.left}px` }
			}
		>
			<div ref={pageRef} inert className="relative min-h-0 flex-1 overflow-y-auto px-4 py-6">
				<div className="mx-auto w-full max-w-6xl">
					{view.page === "profile" ? (
						<PracticeProfilePage
							latestReview={example.latestReview}
							practices={example.practices}
							groups={example.groups}
							feedbackCards={example.feedbackCards}
							ratingProps={exampleRating}
							onOpenGroup={noop}
							onOpenPractice={noop}
							feedbackTab="open"
							onFeedbackTabChange={noop}
							state={{ status: "ready" }}
						/>
					) : (
						<PracticesAcrossTheWorkspacePage
							state={{ status: "ready", overview: example.across.overview }}
							tiles={{ status: "ready", tiles: example.across.tiles }}
							window="DAYS_30"
							onWindowChange={noop}
							onOpenGroup={noop}
						/>
					)}
				</div>
			</div>
			{/* The app's backdrop behind open levels. The levels open without one of their own here. */}
			<div
				aria-hidden
				className={cn(
					"pointer-events-none fixed inset-0 bg-scrim transition-opacity duration-500 motion-reduce:transition-none",
					levelsOpen ? "opacity-100" : "opacity-0",
				)}
			/>
			{view.page === "profile" ? (
				<ProfileLevels
					example={example}
					levels={view.levels ?? []}
					practiceTab={view.practiceTab ?? "feedback"}
				/>
			) : (
				<AcrossLevel example={example} open={view.level === true} />
			)}
		</div>,
		document.body,
	);
}

const PROFILE = { label: "Practice profile", depth: 0 };
const ALL = { label: "All practice groups", depth: 1 };

/** The way across at the foot of each level that has one, as the app shows it. */
const FOOTER_COPY: Partial<
	Record<string, (typeof FOCUS_FOOTER_COPY)[keyof typeof FOCUS_FOOTER_COPY]>
> = {
	"practice-groups": FOCUS_FOOTER_COPY.profileAllGroups,
	"practice-group": FOCUS_FOOTER_COPY.profileGroup,
	practice: FOCUS_FOOTER_COPY.profilePractice,
};

function LevelFooter({ kind }: { kind: string }) {
	const copy = FOOTER_COPY[kind];
	return copy === undefined ? null : (
		<PracticeFocusFooter {...copy} accessibleLabel={copy.label} onSwitch={noop} />
	);
}

/** The app's own drawer levels over the example profile: they slide in and out as the steps change. */
function ProfileLevels({
	example,
	levels,
	practiceTab,
}: {
	example: ExampleProfile;
	levels: readonly TourLevel[];
	practiceTab: PracticeTab;
}) {
	const entries = {
		groups: { kind: "practice-groups", id: "all" },
		group: { kind: "practice-group", id: example.group.slug },
		practice: { kind: "practice", id: example.practice.slug },
		reviews: { kind: "reviews", id: "all" },
	} as const;
	const groupPractices = example.practicesByGroup[example.group.slug] ?? [];
	const feed = {
		status: "ready",
		reviews: example.practiceReviews,
		hasMore: false,
		isLoadingMore: false,
		onLoadMore: noop,
	} satisfies ReviewFeedState;
	const reviewsFeed = { ...feed, reviews: example.reviews } satisfies ReviewFeedState;
	const guidance = { status: "ready", ...example.guidance } satisfies PracticeGuidanceState;
	const groupLevel = { label: example.group.name, depth: 2 };

	return (
		<DetailDrawerStack
			inert
			stack={levels.map((level) => entries[level])}
			onClose={noop}
			footer={(entry) => <LevelFooter kind={entry.kind} />}
		>
			{(entry, level) => {
				switch (entry.kind) {
					case "practice-groups": {
						return (
							<AllPracticeGroupsLevel
								nested={level.nested}
								path={{ behind: [PROFILE], onClose: noop }}
								groups={sortPracticeGroups(
									example.groups,
									example.groupStandings,
									DEFAULT_PRACTICE_GROUP_SORT,
								)}
								standings={example.groupStandings}
								practicesByGroup={example.practicesByGroup}
								sentences={composeOverview(example.overview).groupSentences}
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
								group={example.group}
								standing={example.groupStandings[example.group.slug]}
								practices={groupPractices}
								{...composeGroupOverview(example.overview, example.group.slug)}
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
								path={{ behind: [PROFILE, ALL, groupLevel], onClose: noop }}
								practice={example.practice}
								guidance={guidance}
								feed={feed}
								feedbackCards={example.feedbackCards}
								group={example.group}
								onOpenGroup={noop}
								tab={practiceTab}
								onTabChange={noop}
								skeletonRows={3}
								isLoading={false}
								onRetry={noop}
								ratingProps={exampleRating}
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
								total={example.reviews.length}
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
function AcrossLevel({ example, open }: { example: ExampleProfile; open: boolean }) {
	return (
		<DetailDrawerStack
			inert
			stack={open ? [{ kind: "workspace-group", id: example.across.group.groupSlug }] : []}
			onClose={noop}
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
					state={{ status: "ready", group: example.across.group }}
					onGoToPractice={noop}
				/>
			)}
		</DetailDrawerStack>
	);
}
