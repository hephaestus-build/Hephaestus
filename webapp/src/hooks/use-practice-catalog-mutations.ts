import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	autonomyRollupQueryKey,
	createGroupMutation,
	deleteGroupMutation,
	deletePracticeMutation,
	getPracticeQueryKey,
	listAdoptablePracticesQueryKey,
	listGroupsQueryKey,
	listPracticesOptions,
	listPracticesQueryKey,
	placePracticeMutation,
	previewGroupAdoptionQueryKey,
	reorderGroupsMutation,
	updateGroupMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Practice, PracticeGroup } from "@/api/types.gen";
import {
	applyDisplayOrder,
	applyPracticePlacements,
	byDisplayOrder,
	patchGroup,
	placePractice,
	practiceCatalogStructureScope,
	practicePlacementSnapshot,
	removeGroup,
	selectGroupPatch,
	unassignPractices,
	upsertGroup,
} from "@/hooks/practice-catalog-cache";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

const UNASSIGNED = "__unassigned__";

export function usePracticeCatalogMutations(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const groupsQueryKey = listGroupsQueryKey({ path: { workspaceSlug } });
	const practicesQueryKey = listPracticesQueryKey({ path: { workspaceSlug } });
	const adoptionCatalogQueryKey = listAdoptablePracticesQueryKey({ path: { workspaceSlug } });
	const groupMutationKey = ["practice-catalog", workspaceSlug, "groups"] as const;
	const practiceMutationKey = ["practice-catalog", workspaceSlug, "practices"] as const;
	const structuralScope = practiceCatalogStructureScope(workspaceSlug);
	const applyPlacementCaches = (
		placements: Pick<Practice, "groupSlug" | "displayOrder" | "slug">[],
	) => {
		queryClient.setQueryData<Practice[]>(practicesQueryKey, (practices = []) =>
			applyPracticePlacements(practices, placements),
		);
		for (const placement of placements) {
			queryClient.setQueryData<Practice>(
				getPracticeQueryKey({
					path: { workspaceSlug, practiceSlug: placement.slug },
				}),
				(practice) =>
					practice
						? {
								...practice,
								groupSlug: placement.groupSlug,
								displayOrder: placement.displayOrder,
							}
						: practice,
			);
		}
	};

	const invalidateAutonomyRollup = () => {
		void queryClient.invalidateQueries({
			queryKey: autonomyRollupQueryKey({ path: { workspaceSlug } }),
		});
	};
	const invalidateGroupsAfterLastWrite = () => {
		if (queryClient.isMutating({ mutationKey: groupMutationKey }) === 1) {
			void queryClient.invalidateQueries({ queryKey: groupsQueryKey });
			invalidateAutonomyRollup();
		}
	};
	const invalidatePracticesAfterLastWrite = () => {
		if (queryClient.isMutating({ mutationKey: practiceMutationKey }) === 1) {
			void queryClient.invalidateQueries({ queryKey: practicesQueryKey });
			invalidateAutonomyRollup();
		}
	};

	const createGroup = useMutation({
		...filedUnder(groupMutationKey, createGroupMutation()),
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
			const status = problemStatusOf(error);
			toast.error(
				status === 409 ? "A group with that name already exists" : "Couldn't create the group",
			);
		},
		onSettled: invalidateGroupsAfterLastWrite,
	});

	const updateGroup = useMutation({
		...filedUnder(groupMutationKey, updateGroupMutation()),
		scope: structuralScope,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: groupsQueryKey });
			const previous = queryClient
				.getQueryData<PracticeGroup[]>(groupsQueryKey)
				?.find((group) => group.slug === variables.path.groupSlug);
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				patchGroup(groups, variables.path.groupSlug, variables.body),
			);
			return { previous };
		},
		onError: (_error, variables, context) => {
			const previous = context?.previous;
			if (previous) {
				queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
					patchGroup(groups, previous.slug, selectGroupPatch(previous, variables.body)),
				);
			}
			toast.error("Couldn't update the group");
		},
		onSuccess: (updated, variables) => {
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				patchGroup(groups, updated.slug, selectGroupPatch(updated, variables.body)),
			);
		},
		onSettled: invalidateGroupsAfterLastWrite,
	});

	const deleteGroup = useMutation({
		...filedUnder(groupMutationKey, deleteGroupMutation()),
		scope: structuralScope,
		onMutate: async () => {
			await Promise.all([
				queryClient.cancelQueries({ queryKey: groupsQueryKey }),
				queryClient.cancelQueries({ queryKey: practicesQueryKey }),
			]);
		},
		onSuccess: (_data, variables) => {
			const slug = variables.path.groupSlug;
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				removeGroup(groups, slug),
			);
			const practices = queryClient.getQueryData<Practice[]>(practicesQueryKey) ?? [];
			if (variables.query?.deletePractices === true) {
				const deleted = practices.filter((practice) => practice.groupSlug === slug);
				queryClient.setQueryData<Practice[]>(
					practicesQueryKey,
					practices.filter((practice) => practice.groupSlug !== slug),
				);
				for (const practice of deleted) {
					queryClient.removeQueries({
						queryKey: getPracticeQueryKey({
							path: { workspaceSlug, practiceSlug: practice.slug },
						}),
						exact: true,
					});
				}
				void queryClient.invalidateQueries({ queryKey: adoptionCatalogQueryKey });
			} else {
				const updated = unassignPractices(practices, slug);
				applyPlacementCaches(
					updated.map(({ groupSlug, displayOrder, slug: practiceSlug }) => ({
						groupSlug,
						displayOrder,
						slug: practiceSlug,
					})),
				);
			}
			void queryClient.invalidateQueries({
				queryKey: previewGroupAdoptionQueryKey({ path: { workspaceSlug, slug } }),
			});
			toast.success("Group deleted");
		},
		onError: () => toast.error("Couldn't delete the group"),
		onSettled: () => {
			invalidateGroupsAfterLastWrite();
			if (queryClient.isMutating({ mutationKey: practiceMutationKey }) === 0) {
				void queryClient.invalidateQueries({ queryKey: practicesQueryKey });
			}
		},
	});

	const reorderGroups = useMutation({
		...filedUnder(groupMutationKey, reorderGroupsMutation()),
		scope: structuralScope,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: groupsQueryKey });
			const previousOrder = [...(queryClient.getQueryData<PracticeGroup[]>(groupsQueryKey) ?? [])]
				.sort(byDisplayOrder)
				.map((group) => group.slug);
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				applyDisplayOrder(groups, variables.body.orderedSlugs),
			);
			return { previousOrder };
		},
		onError: (_error, _variables, context) => {
			const previousOrder = context?.previousOrder;
			if (previousOrder) {
				queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
					applyDisplayOrder(groups, previousOrder),
				);
			}
			toast.error("Couldn't reorder the groups");
		},
		onSuccess: (updated) => {
			const order = [...updated].sort(byDisplayOrder).map((group) => group.slug);
			queryClient.setQueryData<PracticeGroup[]>(groupsQueryKey, (groups = []) =>
				applyDisplayOrder(groups, order),
			);
		},
		onSettled: invalidateGroupsAfterLastWrite,
	});

	const place = useMutation({
		...filedUnder(practiceMutationKey, placePracticeMutation()),
		scope: structuralScope,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: practicesQueryKey });
			const practices = queryClient.getQueryData<Practice[]>(practicesQueryKey) ?? [];
			const destinationGroupSlug = variables.body.groupSlug ?? null;
			const previous = practicePlacementSnapshot(
				practices,
				variables.path.practiceSlug,
				destinationGroupSlug,
			);
			const optimistic = placePractice(
				practices,
				variables.path.practiceSlug,
				destinationGroupSlug,
				variables.body.position,
			);
			queryClient.setQueryData(practicesQueryKey, optimistic);
			const affectedSlugs = new Set(previous.map(({ slug }) => slug));
			applyPlacementCaches(
				optimistic
					.filter(({ slug }) => affectedSlugs.has(slug))
					.map(({ slug, groupSlug, displayOrder }) => ({ slug, groupSlug, displayOrder })),
			);
			return { previous };
		},
		onError: (_error, _variables, context) => {
			const previous = context?.previous;
			if (previous) {
				applyPlacementCaches(previous);
			}
			toast.error("Couldn't move the practice");
		},
		onSuccess: (updated) => {
			applyPlacementCaches(
				updated.map(({ slug, groupSlug, displayOrder }) => ({ slug, groupSlug, displayOrder })),
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
					queryKey: getPracticeQueryKey({
						path: {
							workspaceSlug,
							practiceSlug: variables.path.practiceSlug,
						},
					}),
					exact: true,
				}),
			]);
		},
		onSuccess: (_data, variables) => {
			queryClient.setQueryData<Practice[]>(practicesQueryKey, (practices = []) =>
				practices.filter((practice) => practice.slug !== variables.path.practiceSlug),
			);
			queryClient.removeQueries({
				queryKey: getPracticeQueryKey({
					path: {
						workspaceSlug,
						practiceSlug: variables.path.practiceSlug,
					},
				}),
				exact: true,
			});
			void queryClient.invalidateQueries({ queryKey: adoptionCatalogQueryKey });
			toast.success("Practice deleted");
		},
		onError: () => toast.error("Couldn't delete the practice"),
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
		placePractice: place,
		pendingGroupSlugs,
		pendingPracticeSlugs,
		blockedMoveDestinationSlugs,
		blockedPracticeOrderBuckets,
		reorderGroups,
		updateGroup,
	};
}
