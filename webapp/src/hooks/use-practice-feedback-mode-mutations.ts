import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { toast } from "@/lib/toast";

import {
	getFeedbackModeRollupQueryKey,
	listPracticeGroupsQueryKey,
	listPracticesQueryKey,
	setGroupFeedbackModeMutation,
	setPracticeFeedbackModeMutation,
	updatePracticeMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	Practice,
	PracticeDefinition,
	PracticeDefinitionInput,
	PracticeGroup,
} from "@/api/types.gen";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import type { FeedbackMode } from "@/lib/practice-feedback-mode";
import { problemDetailOf } from "@/lib/problem-detail";

export interface BulkProgress {
	done: number;
	total: number;
}

/** Who reviews every selected practice. People need one reason, which every selected practice records. */
export type BulkReviewer = { by: "HEPHAESTUS" | "NOBODY" } | { by: "PEOPLE"; reason: string };

/**
 * The practice's definition with only its reviewer changed. It keeps its review limits, reads, script and scan, so
 * choosing Hephaestus again restores how it was reviewed.
 */
export function withReviewer(definition: PracticeDefinition, reviewer: BulkReviewer): PracticeDefinitionInput {
	return {
		...definition,
		reviewedBy: {
			by: reviewer.by,
			humanReason: reviewer.by === "PEOPLE" ? { description: reviewer.reason.trim() } : null,
			limits: definition.reviewedBy.limits,
		},
	};
}

/** Whether the practice already has this reviewer, so a bulk change leaves it as it is. */
function hasReviewer(practice: Practice, reviewer: BulkReviewer): boolean {
	const current = practice.currentVersion.definition?.reviewedBy;
	return (
		current !== undefined &&
		current.by === reviewer.by &&
		(reviewer.by !== "PEOPLE" || current.humanReason?.description === reviewer.reason.trim())
	);
}

function reportBulk(changed: number, failed: number) {
	if (failed === 0) {
		toast.success(`Changed ${changed} ${changed === 1 ? "practice" : "practices"}`);
	} else if (changed === 0) {
		toast.error(`We could not change ${failed} ${failed === 1 ? "practice" : "practices"}. Try again.`);
	} else {
		toast.warning(`Changed ${changed} of ${changed + failed}. ${failed} could not be changed.`);
	}
}

// Feedback-mode inheritance is server-owned; invalidate its projections rather than predict the chain locally.
export function usePracticeFeedbackModeMutations(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const [bulk, setBulk] = useState<BulkProgress | null>(null);
	// Mutation callbacks capture render state; a ref exposes the practices a bulk write is changing now.
	const bulkPractices = useRef(new Set<string>());
	const groupMutationKey = ["practice-feedback-mode", workspaceSlug, "groups"] as const;
	const practiceMutationKey = ["practice-feedback-mode", workspaceSlug, "practices"] as const;

	const invalidateResolved = () => {
		void queryClient.invalidateQueries({
			queryKey: getFeedbackModeRollupQueryKey({ path: { workspaceSlug } }),
		});
		void queryClient.invalidateQueries({
			queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
		});
	};

	const setGroupFeedbackMode = useMutation({
		...filedUnder(groupMutationKey, setGroupFeedbackModeMutation()),
		onSuccess: (updated) => {
			queryClient.setQueryData<PracticeGroup[]>(
				listPracticeGroupsQueryKey({ path: { workspaceSlug } }),
				(groups) => groups?.map((group) => (group.slug === updated.slug ? updated : group)),
			);
		},
		onError: (error) =>
			toast.error("We could not change the group", { description: problemDetailOf(error) }),
		onSettled: () => {
			if (queryClient.isMutating({ mutationKey: groupMutationKey }) === 1) {
				void queryClient.invalidateQueries({
					queryKey: listPracticeGroupsQueryKey({ path: { workspaceSlug } }),
				});
				invalidateResolved();
			}
		},
	});

	const setPracticeFeedbackMode = useMutation({
		...filedUnder(practiceMutationKey, setPracticeFeedbackModeMutation()),
		// A bulk write reports once, in its summary: a failure stays until it is closed, so one message per
		// practice would leave a pile to close. A single change made meanwhile still reports itself.
		onError: (error, { path }) => {
			if (!bulkPractices.current.has(path.practiceSlug)) {
				toast.error("We could not change the practice", { description: problemDetailOf(error) });
			}
		},
		// Bulk writes share one final refetch instead of refetching after each member write.
		onSettled: (_data, _error, { path }) => {
			if (!bulkPractices.current.has(path.practiceSlug)) {
				invalidateResolved();
			}
		},
	});

	const setManyPracticeFeedbackModes = async (
		practiceSlugs: readonly string[],
		feedbackMode: FeedbackMode | null,
	) => {
		if (practiceSlugs.length === 0) {
			return;
		}
		let failed = 0;
		setBulk({ done: 0, total: practiceSlugs.length });
		for (const [index, practiceSlug] of practiceSlugs.entries()) {
			bulkPractices.current.add(practiceSlug);
			try {
				await setPracticeFeedbackMode.mutateAsync({
					path: { workspaceSlug, practiceSlug },
					body: { feedbackMode },
				});
			} catch {
				failed += 1;
			}
			bulkPractices.current.delete(practiceSlug);
			setBulk({ done: index + 1, total: practiceSlugs.length });
		}
		setBulk(null);
		invalidateResolved();
		reportBulk(practiceSlugs.length - failed, failed);
	};

	const updatePractice = useMutation(updatePracticeMutation());

	/**
	 * Saves each selected practice with the new reviewer, one at a time, as a new version. A practice that a newer
	 * version changed meanwhile is refused, and the summary counts it as not changed.
	 */
	const setManyReviewers = async (practices: readonly Practice[], reviewer: BulkReviewer) => {
		const changing = practices.filter((practice) => !hasReviewer(practice, reviewer));
		if (changing.length === 0) {
			toast.success("Every selected practice already has this reviewer");
			return;
		}
		let failed = 0;
		setBulk({ done: 0, total: changing.length });
		for (const [index, practice] of changing.entries()) {
			// A definition that the server could not read back cannot be saved again from here.
			const definition = practice.currentVersion.definition;
			if (definition === null) {
				failed += 1;
				setBulk({ done: index + 1, total: changing.length });
				continue;
			}
			try {
				await updatePractice.mutateAsync({
					path: { workspaceSlug, practiceSlug: practice.slug },
					body: {
						expectedVersionId: practice.currentVersion.id,
						definition: withReviewer(definition, reviewer),
					},
				});
			} catch {
				failed += 1;
			}
			setBulk({ done: index + 1, total: changing.length });
		}
		setBulk(null);
		invalidateResolved();
		reportBulk(changing.length - failed, failed);
	};

	const pendingGroupSlugs = usePendingMutationIds(groupMutationKey, (variables) =>
		pathString(variables, "groupSlug"),
	);
	const pendingPracticeSlugs = usePendingMutationIds(practiceMutationKey, (variables) =>
		pathString(variables, "practiceSlug"),
	);

	return {
		bulk,
		pendingGroupSlugs,
		pendingPracticeSlugs,
		setGroupFeedbackMode,
		setPracticeFeedbackMode,
		setManyPracticeFeedbackModes,
		setManyReviewers,
	};
}
