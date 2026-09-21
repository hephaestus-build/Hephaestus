import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	createSweepScheduleMutation,
	deleteSweepScheduleMutation,
	listSweepSchedulesQueryKey,
	replaceSweepScheduleMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	CreateReviewSweepScheduleRequest,
	UpdateReviewSweepScheduleRequest,
} from "@/api/types.gen";
import { problemDetailOf } from "@/lib/problem-detail";

export interface SweepScheduleMutations {
	isSaving: boolean;
	onCreate: (request: CreateReviewSweepScheduleRequest) => void;
	onReplace: (scheduleId: string, request: UpdateReviewSweepScheduleRequest) => void;
	onDelete: (scheduleId: string) => void;
}

const failed = (verb: string) => (error: unknown) =>
	toast.error(`Couldn't ${verb} this recurring check`, { description: problemDetailOf(error) });

/**
 * The three writes to a workspace's recurring checks, shaped as the props the schedule editor takes.
 *
 * <p>A recurring check is a standing authorisation to spend, so every write says out loud whether it
 * landed — except a replace, whose result the reader is looking straight at in the row that just
 * changed. The server owns the schedule list (it computes the next run), so each write invalidates
 * rather than writing a guess into the cache.
 */
export function useSweepScheduleMutations(workspaceSlug: string): SweepScheduleMutations {
	const queryClient = useQueryClient();

	const invalidate = async () =>
		queryClient.invalidateQueries({
			queryKey: listSweepSchedulesQueryKey({ path: { workspaceSlug } }),
		});

	const create = useMutation({
		...createSweepScheduleMutation(),
		onSuccess: () => {
			void invalidate();
			toast.success("Recurring check started");
		},
		onError: failed("start"),
	});
	const replace = useMutation({
		...replaceSweepScheduleMutation(),
		onSuccess: () => {
			void invalidate();
		},
		onError: failed("update"),
	});
	const remove = useMutation({
		...deleteSweepScheduleMutation(),
		onSuccess: () => {
			void invalidate();
			toast.success("Recurring check removed");
		},
		onError: failed("remove"),
	});

	return {
		isSaving: create.isPending || replace.isPending || remove.isPending,
		onCreate: (request) => create.mutate({ path: { workspaceSlug }, body: request }),
		onReplace: (scheduleId, request) =>
			replace.mutate({ path: { workspaceSlug, scheduleId }, body: request }),
		onDelete: (scheduleId) => remove.mutate({ path: { workspaceSlug, scheduleId } }),
	};
}
