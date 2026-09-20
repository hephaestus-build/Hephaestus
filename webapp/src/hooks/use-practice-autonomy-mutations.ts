import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { toast } from "sonner";

import {
	autonomyRollupQueryKey,
	listGroupsQueryKey,
	listPracticesQueryKey,
	setAutonomyMutation,
	setGroupAutonomyMutation,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeGroup } from "@/api/types.gen";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import type { PracticeAutonomy } from "@/lib/practice-autonomy";
import { problemDetailOf } from "@/lib/problem-detail";

export interface BulkProgress {
	done: number;
	total: number;
}

/**
 * Every write here invalidates the rollup and the practice list rather than patching them: the
 * inheritance chain is resolved server-side, and a client predicting what a group autonomy does to the
 * practices inheriting it would be a second implementation of that chain. The group response carries
 * the group and nothing else.
 */
export function usePracticeAutonomyMutations(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const [bulk, setBulk] = useState<BulkProgress | null>(null);
	// A ref rather than the state above: the mutation's callbacks close over the render that created
	// them and would keep reading `null` for the whole run.
	const bulkRunning = useRef(false);
	const groupMutationKey = ["practice-autonomy", workspaceSlug, "groups"] as const;
	const practiceMutationKey = ["practice-autonomy", workspaceSlug, "practices"] as const;

	const invalidateResolved = () => {
		void queryClient.invalidateQueries({
			queryKey: autonomyRollupQueryKey({ path: { workspaceSlug } }),
		});
		void queryClient.invalidateQueries({
			queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
		});
	};

	const setGroupAutonomy = useMutation({
		...filedUnder(groupMutationKey, setGroupAutonomyMutation()),
		onSuccess: (updated) => {
			queryClient.setQueryData<PracticeGroup[]>(
				listGroupsQueryKey({ path: { workspaceSlug } }),
				(groups) =>
					groups?.map((group) =>
						group.slug === updated.slug ? { ...group, autonomy: updated.autonomy } : group,
					),
			);
		},
		onError: (error) =>
			toast.error("Couldn't change the group", { description: problemDetailOf(error) }),
		onSettled: () => {
			if (queryClient.isMutating({ mutationKey: groupMutationKey }) === 1) {
				void queryClient.invalidateQueries({
					queryKey: listGroupsQueryKey({ path: { workspaceSlug } }),
				});
				invalidateResolved();
			}
		},
	});

	const setPracticeAutonomy = useMutation({
		...filedUnder(practiceMutationKey, setAutonomyMutation()),
		onError: (error) =>
			toast.error("Couldn't change the practice", { description: problemDetailOf(error) }),
		// One write, one refetch — except inside a bulk run, which settles once at the end rather than
		// refetching the list and the rollup after every PATCH in it.
		onSettled: () => {
			if (!bulkRunning.current) {
				invalidateResolved();
			}
		},
	});

	const setManyPracticeAutonomies = async (
		practiceSlugs: readonly string[],
		autonomy: PracticeAutonomy | null,
	) => {
		if (practiceSlugs.length === 0) {
			return;
		}
		let failed = 0;
		bulkRunning.current = true;
		setBulk({ done: 0, total: practiceSlugs.length });
		for (const [index, practiceSlug] of practiceSlugs.entries()) {
			try {
				await setPracticeAutonomy.mutateAsync({
					path: { workspaceSlug, practiceSlug },
					body: autonomy === null ? {} : { autonomy },
				});
			} catch {
				failed += 1;
			}
			setBulk({ done: index + 1, total: practiceSlugs.length });
		}
		bulkRunning.current = false;
		setBulk(null);
		invalidateResolved();

		const changed = practiceSlugs.length - failed;
		if (failed === 0) {
			toast.success(`Changed ${changed} ${changed === 1 ? "practice" : "practices"}`);
		} else if (changed === 0) {
			toast.error(`Couldn't change ${failed} ${failed === 1 ? "practice" : "practices"}`);
		} else {
			toast.warning(
				`Changed ${changed} of ${practiceSlugs.length}; ${failed} couldn't be changed.`,
			);
		}
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
		setGroupAutonomy,
		setPracticeAutonomy,
		setManyPracticeAutonomies,
	};
}
