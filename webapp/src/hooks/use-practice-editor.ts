import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	bindPracticeGroupMutation,
	createPracticeMutation,
	getPracticeQueryKey,
	listPracticesQueryKey,
	updatePracticeMutation,
} from "@/api/@tanstack/react-query.gen";
import type { CreatePracticeRequest, Practice, UpdatePracticeRequest } from "@/api/types.gen";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

import { practiceCatalogStructureScope, upsertPractice } from "./practice-catalog-cache";
import { refreshPracticeGuidance } from "./practice-guidance-cache";

export interface PracticeEditor {
	create: (request: CreatePracticeRequest) => Promise<Practice>;
	/**
	 * Replaces the definition of the version named in the request, then moves the practice to
	 * `groupSlug` when that differs. The group is where the practice is listed, not in its
	 * definition, so a move never makes a version of its own.
	 */
	update: (
		practice: Practice,
		request: UpdatePracticeRequest,
		groupSlug: string | null,
	) => Promise<Practice>;
	isPending: boolean;
}

/**
 * Saving a practice, wherever the form is hosted.
 *
 * Both calls reject rather than swallowing a failure: the form holds its guard down from submit
 * until it hears one way or the other, so resolving on failure would drop the draft.
 */
export function usePracticeEditor(workspaceSlug: string): PracticeEditor {
	const queryClient = useQueryClient();
	const listQueryKey = listPracticesQueryKey({ path: { workspaceSlug } });
	const scope = practiceCatalogStructureScope(workspaceSlug);

	const update = useMutation({
		...updatePracticeMutation(),
		scope,
		onError: (error) => {
			if (problemStatusOf(error) !== 409) {
				toast.error("We could not save the practice", { description: problemDetailOf(error) });
			}
		},
	});
	const bindGroup = useMutation({
		...bindPracticeGroupMutation(),
		scope,
		onError: (error) =>
			toast.error("The practice was saved, but we could not move it to the group", {
				description: problemDetailOf(error),
			}),
	});
	const create = useMutation({
		...createPracticeMutation(),
		scope,
		onError: (error) =>
			problemStatusOf(error) === 409
				? toast.error("A practice with this identifier already exists in this workspace")
				: toast.error("We could not create the practice", { description: problemDetailOf(error) }),
	});

	const store = async (practice: Practice) => {
		const detailQueryKey = getPracticeQueryKey({
			path: { workspaceSlug, practiceSlug: practice.slug },
		});
		queryClient.setQueryData(detailQueryKey, practice);
		queryClient.setQueryData<Practice[]>(listQueryKey, (practices) =>
			practices ? upsertPractice(practices, practice) : practices,
		);
		void queryClient.invalidateQueries({ queryKey: detailQueryKey });
		void queryClient.invalidateQueries({ queryKey: listQueryKey });
		await refreshPracticeGuidance(queryClient, workspaceSlug, practice.slug);
	};

	return {
		isPending: update.isPending || bindGroup.isPending || create.isPending,
		update: async (practice, request, groupSlug) => {
			const path = { workspaceSlug, practiceSlug: practice.slug };
			await Promise.all([
				queryClient.cancelQueries({ queryKey: getPracticeQueryKey({ path }) }),
				queryClient.cancelQueries({ queryKey: listQueryKey }),
			]);
			const updated = await update.mutateAsync({
				path,
				body: request,
			});
			if (groupSlug === updated.groupSlug) {
				await store(updated);
				toast.success("Practice saved");
				return updated;
			}
			try {
				const placed = await bindGroup.mutateAsync({ path, body: { groupSlug } });
				await store(placed);
				toast.success("Practice saved");
				return placed;
			} catch (error) {
				await store(updated);
				throw error;
			}
		},
		create: async (request) => {
			await queryClient.cancelQueries({ queryKey: listQueryKey });
			const created = await create.mutateAsync({ path: { workspaceSlug }, body: request });
			await store(created);
			toast.success("Practice created");
			return created;
		},
	};
}
