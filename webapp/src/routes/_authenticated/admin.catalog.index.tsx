import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, retainSearchParams, useNavigate } from "@tanstack/react-router";
import { LibraryBig, Plus } from "lucide-react";
import { toast } from "sonner";

import {
	adminGetCuratedCatalogOptions,
	adminGetCuratedCatalogQueryKey,
	adminGetCuratedGroupQueryKey,
	adminGetCuratedPracticeQueryKey,
	adminPlaceCuratedPracticeMutation,
	adminReorderCuratedGroupsMutation,
	adminReorderCuratedPracticesMutation,
	adminResetCuratedCatalogOrderMutation,
	adminUpdateCuratedGroupStatusMutation,
	adminUpdateCuratedPracticeStatusMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	CuratedCatalog,
	CuratedGroup,
	CuratedPractice,
	CuratedPracticeSummary,
} from "@/api/types.gen";
import {
	orderedPracticeSlugs,
	placeCuratedPractice,
	reorderCuratedGroups,
	reorderCuratedPractices,
} from "@/components/admin/curated-catalog/curated-catalog-cache";
import {
	CURATED_CATALOG_SEARCH_PARAMS,
	CURATED_LEVEL_KINDS,
	type CuratedCatalogSearch,
	curatedCatalogSearchSchema,
	curatedGroupLevel,
	curatedPracticeLevel,
	GUARDED_CURATED_LEVEL_KINDS,
} from "@/components/admin/curated-catalog/curated-catalog-search";
import { CuratedCatalogPage } from "@/components/admin/curated-catalog/CuratedCatalogPage";
import { PracticeTreeSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { parseDetailStack } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Button, buttonVariants } from "@/components/ui/button";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

import { CuratedGroupCreateLevel } from "./-CuratedGroupCreateLevel";
import { CuratedGroupEditLevel } from "./-CuratedGroupEditLevel";
import { CuratedPracticeCreateLevel } from "./-CuratedPracticeCreateLevel";
import { CuratedPracticeEditLevel } from "./-CuratedPracticeEditLevel";

export const Route = createFileRoute("/_authenticated/admin/catalog/")({
	head: instanceAdminHead("Practice catalog"),
	validateSearch: curatedCatalogSearchSchema,
	search: { middlewares: [retainSearchParams(CURATED_CATALOG_SEARCH_PARAMS)] },
	component: AdminCuratedCatalogPage,
});

const PRACTICE_STATUS_KEY = ["adminWriteCuratedPracticeStatus"];
const GROUP_STATUS_KEY = ["adminWriteCuratedGroupStatus"];
const STRUCTURE_SCOPE = { id: "admin-curated-catalog-structure" };

function AdminCuratedCatalogPage() {
	const navigate = useNavigate({ from: Route.fullPath });
	const { detail, ...search } = Route.useSearch();
	const queryClient = useQueryClient();
	const detailStack = parseDetailStack(detail, CURATED_LEVEL_KINDS);
	const stackControls = useDetailStack(detailStack);
	const catalogQuery = useQuery({ ...adminGetCuratedCatalogOptions() });

	const detailKey = (kind: "practice" | "group", slug: string) =>
		kind === "practice"
			? adminGetCuratedPracticeQueryKey({ path: { slug } })
			: adminGetCuratedGroupQueryKey({ path: { slug } });
	const invalidateCatalog = () => {
		void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
	};

	const onPracticeStatusSettled = (slug: string, offered: boolean, successMessage?: string) => ({
		onSuccess: (updated: CuratedPractice) => {
			queryClient.setQueryData(detailKey("practice", slug), updated);
			invalidateCatalog();
			toast.success(
				successMessage ??
					(offered ? "Practice offered to workspaces" : "Practice is no longer offered"),
			);
		},
		onError: (error: unknown) => {
			queryClient.removeQueries({ queryKey: detailKey("practice", slug), exact: true });
			invalidateCatalog();
			toast.error(
				problemStatusOf(error) === 412
					? "The catalog changed before this action was saved. We reloaded the practice."
					: "Couldn't update the practice",
				{ description: problemDetailOf(error) },
			);
		},
	});
	const onGroupStatusSettled = (slug: string, offered: boolean) => ({
		onSuccess: (catalog: CuratedCatalog) => {
			queryClient.setQueryData(adminGetCuratedCatalogQueryKey(), catalog);
			queryClient.removeQueries({ queryKey: detailKey("group", slug), exact: true });
			toast.success(offered ? "Group included for workspaces" : "Group is no longer included");
		},
		onError: (error: unknown) => {
			queryClient.removeQueries({ queryKey: detailKey("group", slug), exact: true });
			invalidateCatalog();
			toast.error(
				problemStatusOf(error) === 412
					? "The catalog changed before this action was saved. We reloaded the group."
					: "Couldn't update the group",
				{ description: problemDetailOf(error) },
			);
		},
	});

	const updatePracticeStatus = useMutation(
		filedUnder(PRACTICE_STATUS_KEY, adminUpdateCuratedPracticeStatusMutation()),
	);
	const updateGroupStatus = useMutation(
		filedUnder(GROUP_STATUS_KEY, adminUpdateCuratedGroupStatusMutation()),
	);
	const invalidateStructure = invalidateCatalog;
	const structureError = (error: unknown) => {
		toast.error(
			problemStatusOf(error) === 412
				? "The catalog order changed before this move was saved. We reloaded the latest order."
				: "Couldn't save the catalog order",
			{ description: problemDetailOf(error) },
		);
	};
	const reorderGroups = useMutation({
		...adminReorderCuratedGroupsMutation(),
		scope: STRUCTURE_SCOPE,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
			const previous = queryClient.getQueryData<CuratedCatalog>(adminGetCuratedCatalogQueryKey());
			if (previous) {
				queryClient.setQueryData(
					adminGetCuratedCatalogQueryKey(),
					reorderCuratedGroups(previous, variables.body.orderedSlugs),
				);
			}
			return { previous };
		},
		onSuccess: (catalog) => queryClient.setQueryData(adminGetCuratedCatalogQueryKey(), catalog),
		onError: (error, _variables, context) => {
			if (context?.previous) {
				queryClient.setQueryData(adminGetCuratedCatalogQueryKey(), context.previous);
			}
			structureError(error);
		},
		onSettled: invalidateStructure,
	});
	const reorderPractices = useMutation({
		...adminReorderCuratedPracticesMutation(),
		scope: STRUCTURE_SCOPE,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
			const previous = queryClient.getQueryData<CuratedCatalog>(adminGetCuratedCatalogQueryKey());
			if (previous) {
				const groupSlug = variables.body.groupSlug ?? null;
				queryClient.setQueryData(
					adminGetCuratedCatalogQueryKey(),
					reorderCuratedPractices(previous, groupSlug, variables.body.orderedSlugs),
				);
			}
			return { previous };
		},
		onSuccess: (catalog) => queryClient.setQueryData(adminGetCuratedCatalogQueryKey(), catalog),
		onError: (error, _variables, context) => {
			if (context?.previous) {
				queryClient.setQueryData(adminGetCuratedCatalogQueryKey(), context.previous);
			}
			structureError(error);
		},
		onSettled: invalidateStructure,
	});
	const placePractice = useMutation({
		...adminPlaceCuratedPracticeMutation(),
		scope: STRUCTURE_SCOPE,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
			const previous = queryClient.getQueryData<CuratedCatalog>(adminGetCuratedCatalogQueryKey());
			if (previous) {
				queryClient.setQueryData(
					adminGetCuratedCatalogQueryKey(),
					placeCuratedPractice(
						previous,
						variables.path.slug,
						variables.body.groupSlug ?? null,
						variables.body.position,
					),
				);
			}
			return { previous };
		},
		onSuccess: (catalog, variables) => {
			queryClient.setQueryData(adminGetCuratedCatalogQueryKey(), catalog);
			queryClient.removeQueries({
				queryKey: adminGetCuratedPracticeQueryKey({ path: { slug: variables.path.slug } }),
				exact: true,
			});
		},
		onError: (error, _variables, context) => {
			if (context?.previous) {
				queryClient.setQueryData(adminGetCuratedCatalogQueryKey(), context.previous);
			}
			structureError(error);
		},
		onSettled: invalidateStructure,
	});
	const resetOrder = useMutation({
		...adminResetCuratedCatalogOrderMutation(),
		scope: STRUCTURE_SCOPE,
		onSuccess: (catalog) => {
			queryClient.setQueryData(adminGetCuratedCatalogQueryKey(), catalog);
			toast.success("Hephaestus order restored");
		},
		onError: structureError,
		onSettled: invalidateStructure,
	});
	const pendingPracticeSlugs = usePendingMutationIds(PRACTICE_STATUS_KEY, (variables) =>
		pathString(variables, "slug"),
	);
	const pendingGroupSlugs = usePendingMutationIds(GROUP_STATUS_KEY, (variables) =>
		pathString(variables, "slug"),
	);
	const structurePending =
		reorderGroups.isPending ||
		reorderPractices.isPending ||
		placePractice.isPending ||
		resetOrder.isPending;
	const writePending =
		structurePending || pendingGroupSlugs.size > 0 || pendingPracticeSlugs.size > 0;

	return (
		<PageLayout>
			<PageHeader
				icon={<LibraryBig />}
				title="Practice catalog"
				description="Choose which groups and practices workspace administrators can add. CuratedCatalog changes never rewrite existing workspace practices."
				actions={
					<div className="flex flex-wrap gap-2">
						{writePending ? (
							<Button variant="outline" disabled>
								<Plus className="mr-1.5 size-4" aria-hidden />
								Create group
							</Button>
						) : (
							<DetailStackLink
								entry={curatedGroupLevel()}
								className={buttonVariants({ variant: "outline" })}
							>
								<Plus className="mr-1.5 size-4" aria-hidden />
								Create group
							</DetailStackLink>
						)}
						{writePending ? (
							<Button disabled>
								<Plus className="mr-1.5 size-4" aria-hidden />
								Create practice
							</Button>
						) : (
							<DetailStackLink entry={curatedPracticeLevel()} className={buttonVariants()}>
								<Plus className="mr-1.5 size-4" aria-hidden />
								Create practice
							</DetailStackLink>
						)}
					</div>
				}
			/>

			{catalogQuery.isPending ? (
				<PracticeTreeSkeleton groups={3} practicesPerGroup={3} />
			) : catalogQuery.isError ? (
				<QueryErrorAlert
					error={catalogQuery.error}
					title="Couldn't load the practice catalog"
					onRetry={() => void catalogQuery.refetch()}
				/>
			) : (
				<CuratedCatalogPage
					groups={catalogQuery.data.groups}
					practices={catalogQuery.data.practices}
					summary={catalogQuery.data.summary}
					search={search}
					customOrder={catalogQuery.data.customOrder}
					pendingPracticeSlugs={pendingPracticeSlugs}
					pendingGroupSlugs={pendingGroupSlugs}
					writePending={writePending}
					onSearchChange={(next: CuratedCatalogSearch) =>
						void navigate({ search: (previous) => ({ ...previous, ...next }), replace: true })
					}
					onPracticeStatusChange={(practice: CuratedPracticeSummary, offered) => {
						const parent = hasText(practice.groupSlug)
							? catalogQuery.data.groups.find((group) => group.slug === practice.groupSlug)
							: undefined;
						const availabilityMessage = hasText(practice.groupSlug)
							? parent
								? parent.status.offered
									? undefined
									: "Practice will be included when its group is included"
								: "Move the practice to an included group first"
							: undefined;
						updatePracticeStatus.mutate(
							{
								path: { slug: practice.slug },
								headers: { "If-Match": `"${practice.status.etag}"` },
								body: { status: offered ? "AVAILABLE" : "RETIRED" },
							},
							onPracticeStatusSettled(
								practice.slug,
								offered,
								offered ? availabilityMessage : undefined,
							),
						);
					}}
					onGroupStatusChange={(group: CuratedGroup, offered) =>
						updateGroupStatus.mutate(
							{
								path: { slug: group.slug },
								headers: { "If-Match": `"${catalogQuery.data.etag}"` },
								body: { status: offered ? "AVAILABLE" : "RETIRED" },
							},
							onGroupStatusSettled(group.slug, offered),
						)
					}
					onReorderGroups={(orderedSlugs) =>
						reorderGroups.mutate({
							headers: { "If-Match": `"${catalogQuery.data.etag}"` },
							body: { orderedSlugs },
						})
					}
					onResetOrder={() =>
						resetOrder.mutate({
							headers: { "If-Match": `"${catalogQuery.data.etag}"` },
						})
					}
					onPlacePractice={(practiceSlug, groupSlug, position) => {
						const practice = catalogQuery.data.practices.find(
							(candidate) => candidate.slug === practiceSlug,
						);
						if (!practice) {
							return;
						}
						const optimistic = placeCuratedPractice(
							catalogQuery.data,
							practiceSlug,
							groupSlug,
							position,
						);
						if ((practice.groupSlug ?? null) === groupSlug) {
							reorderPractices.mutate({
								headers: { "If-Match": `"${catalogQuery.data.etag}"` },
								body: { groupSlug, orderedSlugs: orderedPracticeSlugs(optimistic, groupSlug) },
							});
							return;
						}
						placePractice.mutate({
							path: { slug: practiceSlug },
							headers: { "If-Match": `"${catalogQuery.data.etag}"` },
							body: { groupSlug: groupSlug ?? undefined, position },
						});
					}}
				/>
			)}

			<DetailDrawerStack
				stack={detailStack}
				guardedKinds={GUARDED_CURATED_LEVEL_KINDS}
				onClose={stackControls.close}
			>
				{(entry, level) => {
					const done = () => stackControls.close(level.depth);
					if (entry.kind === "practice-new") {
						return <CuratedPracticeCreateLevel nested={level.nested} onDone={done} />;
					}
					if (entry.kind === "practice-edit") {
						return (
							<CuratedPracticeEditLevel
								practiceSlug={entry.id}
								nested={level.nested}
								onDone={done}
							/>
						);
					}
					if (entry.kind === "group-new") {
						return <CuratedGroupCreateLevel nested={level.nested} onDone={done} />;
					}
					return <CuratedGroupEditLevel groupSlug={entry.id} nested={level.nested} onDone={done} />;
				}}
			</DetailDrawerStack>
		</PageLayout>
	);
}
