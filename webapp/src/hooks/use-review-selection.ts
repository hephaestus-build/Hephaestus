import { skipToken, useQuery } from "@tanstack/react-query";

import {
	getItemTraceOptions,
	getReviewFeedbackOptions,
	getReviewOptions,
	getReviewResultOptions,
	listPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ItemRef } from "@/api/types.gen";
import { resultSummary } from "@/components/admin/practice-reviews/result-words";
import { developerLabel } from "@/components/admin/practice-reviews/review-format";
import { type RecordRef, selectionRecord } from "@/components/admin/practice-reviews/review-layers";
import type { ReviewScope } from "@/components/admin/practice-reviews/review-scope";
import {
	type ReviewSelection,
	readScopedRecord,
} from "@/components/admin/practice-reviews/review-selection";
import type { ReviewSelectionName } from "@/components/admin/practice-reviews/ReviewSelectionBar";
import { panelState } from "@/components/common/panel-state";
import { useNow } from "@/components/common/use-now";
import { formatDayTime } from "@/lib/dates";
import { itemName } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

export interface ReviewSelectionRead {
	/** The record the scope narrows to, picked with a crosshair or filtered by. */
	selection: ReviewSelection | undefined;
	/** It was picked with a crosshair: the selection bar shows it, not a filter pill. */
	picked: boolean;
	/** What the selection bar calls it. */
	name: ReviewSelectionName;
	/** What a filter pill calls it, after the kind it names: "#12 · 7 Oct, 14:02" for a review. */
	filterName: string | undefined;
	/** The tracked record, whose trace "Show trace" opens. */
	trace: RecordRef | undefined;
}

type SelectionScope = Pick<
	ReviewScope,
	"reviewId" | "resultId" | "feedbackId" | "selectedPractice" | "itemId" | "selected"
>;

export function useReviewSelection(
	workspaceSlug: string,
	scope: SelectionScope,
): ReviewSelectionRead {
	const read = useRecordRead(workspaceSlug, scope);
	const named = read.name.status === "ready" ? read.name.name : undefined;
	return {
		...read,
		filterName: read.selection?.kind === "review" ? named?.replace(REVIEW_NAME_PREFIX, "") : named,
	};
}

/** The selection bar says "Review of #12 · …"; a pill already says "Review:". */
const REVIEW_NAME_PREFIX = /^Review of /u;

function useRecordRead(
	workspaceSlug: string,
	scope: SelectionScope,
): Omit<ReviewSelectionRead, "filterName"> {
	const selection = readScopedRecord(scope);
	const picked = selection !== undefined && scope.selected === true;
	const nowMs = useNow();
	const path = { workspaceSlug };

	const resultId = selection?.kind === "result" ? selection.resultId : undefined;
	const resultOptions = getReviewResultOptions({ path: { ...path, resultId: resultId ?? "" } });
	const resultQuery = useQuery({
		...resultOptions,
		queryFn: resultId === undefined ? skipToken : resultOptions.queryFn,
	});

	const reviewId = selection?.kind === "review" ? selection.reviewId : undefined;
	const reviewOptions = getReviewOptions({ path: { ...path, reviewId: reviewId ?? "" } });
	const reviewQuery = useQuery({
		...reviewOptions,
		queryFn: reviewId === undefined ? skipToken : reviewOptions.queryFn,
	});

	const feedbackId = selection?.kind === "feedback" ? selection.feedbackId : undefined;
	const feedbackOptions = getReviewFeedbackOptions({
		path: { ...path, feedbackId: feedbackId ?? "" },
	});
	const feedbackQuery = useQuery({
		...feedbackOptions,
		queryFn: feedbackId === undefined ? skipToken : feedbackOptions.queryFn,
	});

	const practicesOptions = listPracticesOptions({ path });
	const practicesQuery = useQuery({
		...practicesOptions,
		queryFn: selection?.kind === "practice" ? practicesOptions.queryFn : skipToken,
	});

	const itemId = selection?.kind === "item" ? selection.itemId : undefined;
	const itemOptions = getItemTraceOptions({ path: { ...path, itemId: itemId ?? "" } });
	const itemQuery = useQuery({
		...itemOptions,
		queryFn: itemId === undefined ? skipToken : itemOptions.queryFn,
	});
	const trace = selection === undefined ? undefined : selectionRecord(selection);

	switch (selection?.kind) {
		case undefined: {
			return { selection, picked, name: { status: "loading" }, trace: undefined };
		}
		case "item": {
			return {
				selection,
				picked,
				name: panelState(itemQuery, ({ item }) => ({
					status: "ready" as const,
					name: workName(item),
				})),
				trace,
			};
		}
		case "review": {
			return {
				selection,
				picked,
				name: panelState(reviewQuery, (review) => ({
					status: "ready" as const,
					name: `Review of ${review.item.label} · ${formatDayTime(review.createdAt, new Date(nowMs))}`,
				})),
				trace,
			};
		}
		case "result": {
			return {
				selection,
				picked,
				name: panelState(resultQuery, (result) => ({
					status: "ready" as const,
					name: resultSummary({ summary: result.summary ?? undefined }),
				})),
				trace,
			};
		}
		case "feedback": {
			return {
				selection,
				picked,
				name: panelState(feedbackQuery, (feedback) => ({
					status: "ready" as const,
					name: `${feedback.person === null ? "Feedback" : `Feedback for ${developerLabel(feedback.person)}`} · ${formatDayTime(feedback.createdAt, new Date(nowMs))}`,
				})),
				trace,
			};
		}
		case "practice": {
			return {
				selection,
				picked,
				name: panelState(practicesQuery, (practices) => {
					const practice = practices.find((entry) => entry.slug === selection.practiceSlug);
					return {
						status: "ready" as const,
						name: practice?.currentVersion.name ?? selection.practiceSlug,
					};
				}),
				trace,
			};
		}
	}
}

function workName(item: ItemRef): string {
	return [itemName(item), item.title].filter(hasText).join(" · ");
}
