import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	createPracticeGroupMutation,
	deletePracticeGroupMutation,
	deletePracticeMutation,
	getFeedbackModeRollupQueryKey,
	getPracticeGroupQueryKey,
	getPracticeGuidanceQueryKey,
	getPracticeQueryKey,
	getPracticeReleaseQueryKey,
	listAdoptablePracticesQueryKey,
	listPracticeGroupsQueryKey,
	listPracticeReleasesQueryKey,
	listPracticeScriptNeedsQueryKey,
	listPracticesOptions,
	listPracticesQueryKey,
	listPracticeVersionsQueryKey,
	movePracticeMutation,
	previewGroupAdoptionQueryKey,
	reorderPracticeGroupsMutation,
	resetGroupToCatalogMutation,
	resetPracticeToCatalogMutation,
	updatePracticeGroupMutation,
} from "@/api/@tanstack/react-query.gen";
import { getPracticeGroup } from "@/api/sdk.gen";
import type { Practice, PracticeGroup } from "@/api/types.gen";
import {
	applyPositions,
	applyPracticePositions,
	byPosition,
	movePractice,
	practiceCatalogStructureScope,
	practicePositionSnapshot,
	removeGroup,
	unassignPractices,
	upsertGroup,
	upsertPractice,
	withGroupDetails,
} from "@/hooks/practice-catalog-cache";
import { refreshPracticeGuidance } from "@/hooks/practice-guidance-cache";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

const UNASSIGNED = "__unassigned__";

export function practiceGroupResponseOptions(path: { workspaceSlug: string; groupSlug: string }) {
	return queryOptions({
		queryKey: getPracticeGroupQueryKey({ path }),
		queryFn: async ({ signal }) => {
			const { data: group, response } = await getPracticeGroup({
				path,
				signal,
				throwOnError: true,
			});
			const etag = response.headers.get("ETag");
			if (etag === null) {
				throw new Error("The group did not include its current version.");
			}
			return { group, etag };
		},
	});
}

export function usePracticeCatalogMutations(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const groupsQueryKey = listPracticeGroupsQueryKey({ path: { workspaceSlug } });
	const practicesQueryKey = listPracticesQueryKey({ path: { workspaceSlug } });
	const adoptionCatalogQueryKey = listAdoptablePracticesQueryKey({ path: { workspaceSlug } });
	const groupMutationKey = ["practice-catalog", workspaceSlug, "groups"] as const;
	const practiceMutationKey = ["practice-catalog", workspaceSlug, "practices"] as const;
	const structuralScope = practiceCatalogStructureScope(workspaceSlug);
	const practiceQueryKey = (practiceSlug: string) =>
		getPracticeQueryKey({ path: { workspaceSlug, practiceSlug } });
	const applyPositionCaches = (positions: Pick<Practice, "groupSlug" | "position" | "slug">[]) => {
		queryClient.setQueryData<Practice[]>(practicesQueryKey, (practices = []) =>
			applyPracticePositions(practices, positions),
		);
		for (const { slug, groupSlug, position } of positions) {
			queryClient.setQueryData<Practice>(practiceQueryKey(slug), (practice) =>
				practice ? { ...practice, groupSlug, position } : practice,
			);
		}
	};
	const storePractice = (practice: Practice) => {
		queryClient.setQueryData(practiceQueryKey(practice.slug), practice);
		queryClient.setQueryData<Practice[]>(practicesQueryKey, (practices) =>
			practices ? upsertPractice(practices, practice) : practices,
		);
	};

	const invalidateFeedbackModeRollup = () => {
		void queryClient.invalidateQueries({
			queryKey: getFeedbackModeRollupQueryKey({ path: { workspaceSlug } }),
		});
	};
	const invalidateGroupsAfterLastWrite = () => {
		if (queryClient.isMutating({ mutationKey: groupMutationKey }) === 1) {
			void queryClient.invalidateQueries({ queryKey: groupsQueryKey });
			invalidateFeedbackModeRollup();
		}
	};
	const invalidatePracticesAfterLastWrite = () => {
		if (queryClient.isMutating({ mutationKey: practiceMutationKey }) === 1) {
			void queryClient.invalidateQueries({ queryKey: practicesQueryKey });
			invalidateFeedbackModeRollup();
		}
	};

	const createGroup = useMutation({
		...filedUnder(groupMutationKey, createPracticeGroupMutation()),
		scope: structuralScope,
		onMutate: async () => {
			await queryClient.cancelQueries({ queryKey: groupsQueryKey });
		},
		onSuccess: (created) => {
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups) =>
				groups ? upsertGroup(groups, created) : groups,
			);
			toast.success("Group created");
		},
		onError: (error) => {
			toast.error(
				problemStatusOf(error) === 409
					? "A group with that name already exists"
					: "We could not create the group. Try again.",
			);
		},
		onSettled: invalidateGroupsAfterLastWrite,
	});

	const updateGroup = useMutation({
		...filedUnder(groupMutationKey, updatePracticeGroupMutation()),
		scope: structuralScope,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: groupsQueryKey });
			const previous = queryClient
				.getQueryData<PracticeGroup[]>(groupsQueryKey)
				?.find((group) => group.slug === variables.path.groupSlug);
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				withGroupDetails(groups, variables.path.groupSlug, variables.body),
			);
			return { previous };
		},
		onError: (_error, _variables, context) => {
			const previous = context?.previous;
			if (previous) {
				queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
					upsertGroup(groups, previous),
				);
			}
			toast.error("We could not update the group. Try again.");
		},
		onSuccess: (updated) => {
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				upsertGroup(groups, updated),
			);
		},
		onSettled: invalidateGroupsAfterLastWrite,
	});

	const deleteGroup = useMutation({
		...filedUnder(groupMutationKey, deletePracticeGroupMutation()),
		scope: structuralScope,
		onMutate: async () => {
			await Promise.all([
				queryClient.cancelQueries({ queryKey: groupsQueryKey }),
				queryClient.cancelQueries({ queryKey: practicesQueryKey }),
			]);
		},
		onSuccess: async (_data, variables) => {
			const slug = variables.path.groupSlug;
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				removeGroup(groups, slug),
			);
			const practices = queryClient.getQueryData<Practice[]>(practicesQueryKey) ?? [];
			const affected = practices.filter((practice) => practice.groupSlug === slug);
			applyPositionCaches(
				unassignPractices(practices, slug).map(({ groupSlug, position, slug: practiceSlug }) => ({
					groupSlug,
					position,
					slug: practiceSlug,
				})),
			);
			await Promise.all(
				affected.map(async (practice) => {
					const filters = { queryKey: practiceQueryKey(practice.slug), exact: true };
					// Cancel the in-flight read before refetching the reset basis; cache reuse can otherwise return the old basis.
					await queryClient.cancelQueries(filters);
					await queryClient.invalidateQueries(filters);
				}),
			);
			void queryClient.invalidateQueries({
				queryKey: previewGroupAdoptionQueryKey({ path: { workspaceSlug, slug } }),
			});
			toast.success("Group deleted");
		},
		onError: () => toast.error("We could not delete the group. Try again."),
		onSettled: () => {
			invalidateGroupsAfterLastWrite();
			if (queryClient.isMutating({ mutationKey: practiceMutationKey }) === 0) {
				void queryClient.invalidateQueries({ queryKey: practicesQueryKey });
			}
		},
	});

	const reorderGroups = useMutation({
		...filedUnder(groupMutationKey, reorderPracticeGroupsMutation()),
		scope: structuralScope,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: groupsQueryKey });
			const previousOrder = [...(queryClient.getQueryData<PracticeGroup[]>(groupsQueryKey) ?? [])]
				.sort(byPosition)
				.map((group) => group.slug);
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				applyPositions(groups, variables.body.slugs),
			);
			return { previousOrder };
		},
		onError: (_error, _variables, context) => {
			const previousOrder = context?.previousOrder;
			if (previousOrder) {
				queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
					applyPositions(groups, previousOrder),
				);
			}
			toast.error("We could not reorder the groups. Try again.");
		},
		onSuccess: (updated) => {
			const order = [...updated].sort(byPosition).map((group) => group.slug);
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				applyPositions(groups, order),
			);
		},
		onSettled: invalidateGroupsAfterLastWrite,
	});

	/** Reset requires the group read's ETag and the exact catalog version the reader compared. */
	const resetGroup = useMutation({
		...filedUnder(groupMutationKey, resetGroupToCatalogMutation()),
		scope: structuralScope,
		onSuccess: (reset) => {
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				upsertGroup(groups, reset),
			);
			void queryClient.invalidateQueries({
				queryKey: getPracticeGroupQueryKey({ path: { workspaceSlug, groupSlug: reset.slug } }),
			});
			toast.success("Group reset to the catalog");
		},
		onSettled: invalidateGroupsAfterLastWrite,
	});

	const resetPractice = useMutation({
		...filedUnder(practiceMutationKey, resetPracticeToCatalogMutation()),
		scope: structuralScope,
		onSuccess: async (reset) => {
			storePractice(reset);
			const path = { workspaceSlug, practiceSlug: reset.slug };
			await Promise.all([
				queryClient.invalidateQueries({ queryKey: listPracticeVersionsQueryKey({ path }) }),
				queryClient.invalidateQueries({
					queryKey: getPracticeReleaseQueryKey({ path: { workspaceSlug, slug: reset.slug } }),
				}),
				queryClient.invalidateQueries({
					queryKey: listPracticeReleasesQueryKey({ path: { workspaceSlug } }),
				}),
				queryClient.invalidateQueries({
					queryKey: listPracticeScriptNeedsQueryKey({ path: { workspaceSlug } }),
				}),
				refreshPracticeGuidance(queryClient, workspaceSlug, reset.slug),
			]);
			toast.success("Practice reset to the catalog", {
				description: `Saved as version ${reset.currentVersion.number}.`,
			});
		},
		onSettled: invalidatePracticesAfterLastWrite,
	});

	const place = useMutation({
		...filedUnder(practiceMutationKey, movePracticeMutation()),
		scope: structuralScope,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: practicesQueryKey });
			const practices = queryClient.getQueryData<Practice[]>(practicesQueryKey) ?? [];
			const destinationGroupSlug = variables.body.groupSlug;
			const previous = practicePositionSnapshot(
				practices,
				variables.path.practiceSlug,
				destinationGroupSlug,
			);
			const optimistic = movePractice(
				practices,
				variables.path.practiceSlug,
				destinationGroupSlug,
				variables.body.position,
			);
			queryClient.setQueryData(practicesQueryKey, optimistic);
			const affectedSlugs = new Set(previous.map(({ slug }) => slug));
			applyPositionCaches(
				optimistic
					.filter(({ slug }) => affectedSlugs.has(slug))
					.map(({ slug, groupSlug, position }) => ({ slug, groupSlug, position })),
			);
			return { previous };
		},
		onError: (_error, _variables, context) => {
			const previous = context?.previous;
			if (previous) {
				applyPositionCaches(previous);
			}
			toast.error("We could not move the practice. Try again.");
		},
		onSuccess: (updated) => {
			applyPositionCaches(
				updated.map(({ slug, groupSlug, position }) => ({ slug, groupSlug, position })),
			);
		},
		onSettled: invalidatePracticesAfterLastWrite,
	});

	const deletePractice = useMutation({
		...filedUnder(practiceMutationKey, deletePracticeMutation()),
		scope: structuralScope,
		onMutate: async (variables) => {
			await Promise.all([
				queryClient.cancelQueries({ queryKey: practicesQueryKey }),
				queryClient.cancelQueries({
					queryKey: practiceQueryKey(variables.path.practiceSlug),
					exact: true,
				}),
			]);
		},
		onSuccess: (_data, variables) => {
			const { practiceSlug } = variables.path;
			queryClient.removeQueries({
				queryKey: getPracticeGuidanceQueryKey({ path: { workspaceSlug, practiceSlug } }),
				exact: true,
			});
			queryClient.setQueryData<Practice[]>(practicesQueryKey, (practices = []) =>
				practices.filter((practice) => practice.slug !== practiceSlug),
			);
			queryClient.removeQueries({ queryKey: practiceQueryKey(practiceSlug), exact: true });
			void queryClient.invalidateQueries({ queryKey: adoptionCatalogQueryKey });
			toast.success("Practice deleted");
		},
		onError: () => toast.error("We could not delete the practice. Try again."),
		onSettled: invalidatePracticesAfterLastWrite,
	});

	const { data: practices = [] } = useQuery({
		...listPracticesOptions({ path: { workspaceSlug } }),
		select: (all) => all.map(({ slug, groupSlug }) => ({ slug, groupSlug })),
	});
	const pendingGroupSlugs = usePendingMutationIds(groupMutationKey, (variables) =>
		pathString(variables, "groupSlug"),
	);
	const pendingPracticeSlugs = usePendingMutationIds(practiceMutationKey, (variables) =>
		pathString(variables, "practiceSlug"),
	);
	const blockedPracticeOrderBuckets = new Set<string>();
	const blockedMoveDestinationSlugs = new Set<string>();
	if (place.isPending) {
		blockedPracticeOrderBuckets.add(UNASSIGNED);
		blockedMoveDestinationSlugs.add(UNASSIGNED);
		for (const practice of practices) {
			if (hasText(practice.groupSlug)) {
				blockedPracticeOrderBuckets.add(practice.groupSlug);
				blockedMoveDestinationSlugs.add(practice.groupSlug);
			}
		}
	}
	if (deleteGroup.isPending) {
		const deletingGroupSlug = deleteGroup.variables.path.groupSlug;
		blockedPracticeOrderBuckets.add(deletingGroupSlug);
		blockedPracticeOrderBuckets.add(UNASSIGNED);
		blockedMoveDestinationSlugs.add(deletingGroupSlug);
		blockedMoveDestinationSlugs.add(UNASSIGNED);
	}
	if (deletePractice.isPending) {
		const practice = practices.find(
			({ slug }) => slug === deletePractice.variables.path.practiceSlug,
		);
		const bucket = practice?.groupSlug ?? UNASSIGNED;
		blockedPracticeOrderBuckets.add(bucket);
		blockedMoveDestinationSlugs.add(bucket);
	}
	return {
		groupStructurePending:
			createGroup.isPending || deleteGroup.isPending || reorderGroups.isPending || place.isPending,
		createGroup,
		deleteGroup,
		deletePractice,
		movePractice: place,
		pendingGroupSlugs,
		pendingPracticeSlugs,
		blockedMoveDestinationSlugs,
		blockedPracticeOrderBuckets,
		reorderGroups,
		resetGroup,
		resetPractice,
		updateGroup,
	};
}
