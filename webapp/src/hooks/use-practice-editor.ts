import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	createPracticeMutation,
	getPracticeQueryKey,
	listPracticesQueryKey,
	updatePracticeMutation,
} from "@/api/@tanstack/react-query.gen";
import type { CreatePracticeRequest, Practice, UpdatePracticeRequest } from "@/api/types.gen";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

import {
	patchPractice,
	practiceCatalogStructureScope,
	selectPracticePatch,
	upsertPractice,
} from "./practice-catalog-cache";

export interface PracticeEditor {
	create: (data: CreatePracticeRequest, groupSlug: string | null) => Promise<Practice>;
	update: (
		slug: string,
		data: UpdatePracticeRequest,
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
		onError: (error) =>
			toast.error("We could not save the practice", { description: problemDetailOf(error) }),
	});
	const create = useMutation({
		...createPracticeMutation(),
		scope,
		onError: (error) =>
			problemStatusOf(error) === 409
				? toast.error("A practice with this identifier already exists in this workspace")
				: toast.error("We could not create the practice", { description: problemDetailOf(error) }),
	});

	return {
		isPending: update.isPending || create.isPending,
		update: async (slug, data, groupSlug) => {
			const request = { ...data, group: { groupSlug } };
			const detailQueryKey = getPracticeQueryKey({ path: { workspaceSlug, practiceSlug: slug } });
			await Promise.all([
				queryClient.cancelQueries({ queryKey: detailQueryKey }),
				queryClient.cancelQueries({ queryKey: listQueryKey }),
			]);
			const updated = await update.mutateAsync({
				path: { workspaceSlug, practiceSlug: slug },
				body: request,
			});
			queryClient.setQueryData(detailQueryKey, updated);
			queryClient.setQueryData<Practice[]>(listQueryKey, (practices) =>
				practices
					? patchPractice(practices, updated.slug, selectPracticePatch(updated, request))
					: practices,
			);
			void queryClient.invalidateQueries({ queryKey: detailQueryKey });
			void queryClient.invalidateQueries({ queryKey: listQueryKey });
			toast.success("Practice saved");
			return updated;
		},
		create: async (data, groupSlug) => {
			await queryClient.cancelQueries({ queryKey: listQueryKey });
			const created = await create.mutateAsync({
				path: { workspaceSlug },
				body: { ...data, groupSlug },
			});
			queryClient.setQueryData<Practice[]>(listQueryKey, (practices) =>
				practices ? upsertPractice(practices, created) : practices,
			);
			void queryClient.invalidateQueries({ queryKey: listQueryKey });
			toast.success("Practice created");
			return created;
		},
	};
}
