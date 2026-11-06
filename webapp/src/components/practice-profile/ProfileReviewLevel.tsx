import type { ReactNode } from "react";
import { definitionFor } from "@/components/common/status-def";

import type { GroupRef, ItemRef, ProfileReviewDetail, ResultList } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import {
	DetailDrawerHeader,
	LevelPathRow,
} from "@/components/layout/detail-drawer/DetailDrawerHeader";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { ReviewOrdinalTag, ReviewOriginTag } from "@/components/practice-trace/review-tags";
import {
	type ReviewPracticeFilters,
	earlierReviewOf,
	ReviewPracticeTable,
	ReviewPracticeTableSkeleton,
} from "@/components/practice-trace/ReviewPracticeTable";
import {
	isActiveReview,
	OWN_REVIEW_STATUS_DEFS,
} from "@/components/practice-vocabulary/review-status-defs";
import { ReviewStatusBadge } from "@/components/practice-vocabulary/ReviewStatusBadge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { Separator } from "@/components/ui/separator";
import { formatDayTime } from "@/lib/dates";
import { itemKindLabel, itemKindNoun } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

import { REVIEW_SELECTION_CLEARED, reviewLevel } from "./practice-profile-search";

export type ProfileReviewDetailState = PanelState<{ detail: ProfileReviewDetail }>;

export interface ProfileReviewLevelProps {
	nested?: boolean;
	path: LevelPath;
	state: ProfileReviewDetailState;
	positionOnWork?: number;
	/** How many reviews the work has, when the list knows: "Review 2 of 3". */
	totalOnWork?: number;
	groupOf: (practiceSlug: string) => GroupRef | undefined;
	onOpenPractice: (practiceSlug: string) => void;
	filters: ReviewPracticeFilters;
	onFiltersChange: (filters: ReviewPracticeFilters) => void;
	observationLink?: (result: ResultList) => ReactNode;
	/** The observation open in a practice level over this review, which its row marks. */
	openedObservationId?: string;
}

const SKELETON_ROWS = 4;

/**
 * Swapped in for this review rather than opened over it: the review level is read off the stack by
 * kind, so a second review over the first would never be the one shown.
 */
function earlierReviewLink(reviewId: string) {
	return (
		<InlineLink
			render={
				<DetailStackLink
					entry={reviewLevel(reviewId)}
					swap
					levelSearch={REVIEW_SELECTION_CLEARED}
				/>
			}
			className="text-sm"
		>
			Open the earlier review
		</InlineLink>
	);
}

export function ProfileReviewLevel({
	nested,
	path,
	state,
	positionOnWork,
	totalOnWork,
	groupOf,
	onOpenPractice,
	filters,
	onFiltersChange,
	observationLink,
	openedObservationId,
}: ProfileReviewLevelProps) {
	const detail = state.status === "ready" ? state.detail : undefined;

	let body: ReactNode;
	if (state.status === "error") {
		body = (
			<QueryErrorAlert
				error={state.error}
				title="We could not load this review"
				onRetry={state.onRetry}
			/>
		);
	} else if (state.status === "loading") {
		body = <ReviewPracticeTableSkeleton rows={SKELETON_ROWS} />;
	} else {
		// The review lists every practice it reached for the reader, and nothing else: that is the
		// most a developer wants from one review. What it noticed on the work stays in the admin trace.
		// The feedback the review gave sits with its observation in the practice, where it can be
		// read and answered; a list of it here could not be narrowed, so the review leaves it out.
		const { review, results } = state.detail;
		const unchanged =
			!isActiveReview(review.status) &&
			results.length > 0 &&
			results.every((result) => earlierReviewOf(result, review.id) !== undefined);
		body = (
			<>
				{unchanged && (
					<Alert role="note">
						<AlertTitle>Kept from the last review</AlertTitle>
						<AlertDescription>
							Nothing these practices read has changed, so Hephaestus did not review them again.
							These observations still stand.
						</AlertDescription>
					</Alert>
				)}
				<ReviewPracticeTable
					results={results}
					reviewId={review.id}
					groupOf={groupOf}
					filters={filters}
					onFiltersChange={onFiltersChange}
					onOpenPractice={onOpenPractice}
					earlierReviewLink={earlierReviewLink}
					observationLink={observationLink}
					openedObservationId={openedObservationId}
					emptyMessage={
						isActiveReview(review.status)
							? "Observations appear here as the review reaches each practice."
							: "This review recorded no observation about you, so there is nothing to list here."
					}
				/>
			</>
		);
	}

	return (
		<>
			<DetailDrawerHeader nested={nested} dismiss="inPath">
				<ReviewHead
					path={path}
					detail={detail}
					positionOnWork={positionOnWork}
					totalOnWork={totalOnWork}
				/>
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-4 pt-2">
				{/* The rule between what was reviewed and what the review found. */}
				<Separator />
				{body}
			</DrawerBody>
		</>
	);
}

/** The heading's words before the number; untitled work is headed by its kind, "Merge request". */
function headTitle(item: ItemRef | undefined, number: string | undefined): string {
	if (hasText(item?.title)) {
		return item.title;
	}
	if (number === undefined) {
		return item?.label ?? "Review";
	}
	return itemKindLabel(item?.kind, 1, item?.provider);
}

interface ReviewHeadProps {
	path: LevelPath;
	detail: ProfileReviewDetail | undefined;
	positionOnWork: number | undefined;
	totalOnWork: number | undefined;
}

/**
 * The review's head: where the work lives, then its title with the provider's number after it as
 * the link to the original, as GitHub heads a pull request, then when and how it ran. No asking here: the list one level down asks for another review of the work.
 */
function ReviewHead({ path, detail, positionOnWork, totalOnWork }: ReviewHeadProps) {
	const today = new Date(useNow());
	const review = detail?.review;
	const item = review?.item;
	const at = review?.completedAt ?? review?.createdAt;
	const ItemIcon = reviewedWorkIcon(item?.kind, item?.provider ?? undefined);
	// The provider's own number, as it writes it ("#19", "!19" for a GitLab merge request), after the
	// title as GitHub heads a pull request; a kind without one, such as a document, has none.
	const number = item !== undefined && /^[#!]\d+$/u.test(item.label) ? item.label : undefined;
	const title = headTitle(item, number);
	const unnumberedTitle = number === undefined ? title : undefined;
	// Over the title: the repository, or the kind where the work names none.
	const where = hasText(item?.container)
		? item.container
		: itemKindLabel(item?.kind, 1, item?.provider);

	return (
		<div className="flex min-w-0 flex-1 flex-col gap-2">
			<LevelPathRow {...path} current="Review" />
			<p className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
				<ItemIcon className="size-3.5 shrink-0" />
				<span className="truncate">{where}</span>
			</p>
			<DrawerTitle
				// Read as "Add dark mode toggle to settings, pull request #19"; the number inside stays the
				// link to the original, with its own name.
				aria-label={
					item !== undefined && number !== undefined && hasText(item.title)
						? `${item.title}, ${itemKindNoun(item.kind, 1, item.provider)} ${number}`
						: unnumberedTitle
				}
				className="text-2xl font-semibold tracking-tight break-words"
			>
				{number === undefined && hasText(item?.url) ? (
					<InlineLink href={item.url} external>
						{title}
					</InlineLink>
				) : (
					title
				)}
				{number !== undefined && (
					<>
						{" "}
						{hasText(item?.url) ? (
							<InlineLink href={item.url} external className="font-normal text-muted-foreground">
								{number}
							</InlineLink>
						) : (
							<span className="font-normal text-muted-foreground">{number}</span>
						)}
					</>
				)}
			</DrawerTitle>
			<div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-sm text-muted-foreground">
				{at && (
					<span>
						{review?.completedAt === null ? "Created" : "Reviewed"}{" "}
						<time dateTime={at.toISOString()} className="text-foreground">
							{formatDayTime(at, today)}
						</time>
					</span>
				)}
				{review && <ReviewStatusBadge def={definitionFor(OWN_REVIEW_STATUS_DEFS, review.status)} />}
				{review !== undefined && review.origin !== "EVENT" && (
					<ReviewOriginTag origin={review.origin} />
				)}
				<ReviewOrdinalTag position={positionOnWork} total={totalOnWork} />
			</div>
		</div>
	);
}
