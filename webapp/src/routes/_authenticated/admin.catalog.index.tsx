import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, retainSearchParams, useNavigate } from "@tanstack/react-router";
import { LibraryBig, Plus } from "lucide-react";
import type { ReactNode } from "react";
import { toast } from "@/lib/toast";

import {
	getCatalogGroupQueryKey,
	getCatalogPracticeQueryKey,
	getInstanceCatalogOptions,
	getInstanceCatalogQueryKey,
	moveCatalogPracticeMutation,
	reorderCatalogGroupsMutation,
	resetCatalogOrderMutation,
	setCatalogGroupStatusMutation,
	setCatalogPracticeStatusMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Catalog, CatalogGroup, CatalogPractice } from "@/api/types.gen";
import {
	placeCatalogPractice,
	reorderCatalogGroups,
} from "@/components/admin/catalog/catalog-cache";
import {
	CATALOG_LEVEL_KINDS,
	CATALOG_LEVEL_LABELS,
	CATALOG_SEARCH_PARAMS,
	type CatalogSearch,
	catalogGroupLevel,
	catalogPracticeLevel,
	catalogSearchSchema,
	GUARDED_CATALOG_LEVEL_KINDS,
} from "@/components/admin/catalog/catalog-search";
import { CatalogPage } from "@/components/admin/catalog/CatalogPage";
import { PracticeTreeSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { parseDetailStack } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Button, buttonVariants } from "@/components/ui/button";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

import { CatalogGroupCreateLevel } from "./-CatalogGroupCreateLevel";
import { CatalogGroupEditLevel } from "./-CatalogGroupEditLevel";
import { CatalogPracticeCreateLevel } from "./-CatalogPracticeCreateLevel";
import { CatalogPracticeEditLevel } from "./-CatalogPracticeEditLevel";

export const Route = createFileRoute("/_authenticated/admin/catalog/")({
	head: instanceAdminHead("Practice catalog"),
	validateSearch: catalogSearchSchema,
	search: { middlewares: [retainSearchParams(CATALOG_SEARCH_PARAMS)] },
	component: AdminCatalogPage,
});

const PRACTICE_STATUS_KEY = ["adminWriteCatalogPracticeStatus"];
const GROUP_STATUS_KEY = ["adminWriteCatalogGroupStatus"];
const STRUCTURE_SCOPE = { id: "admin-catalog-structure" };

function detailKey(kind: "practice" | "group", slug: string) {
	return kind === "practice"
		? getCatalogPracticeQueryKey({ path: { slug } })
		: getCatalogGroupQueryKey({ path: { slug } });
}

function structureError(error: unknown) {
	toast.error(
		problemStatusOf(error) === 409
			? "The catalog order changed before this move was saved. We reloaded the latest order."
			: "We could not save the catalog order",
		{ description: problemDetailOf(error) },
	);
}

/** Why offering a practice may not reach workspaces yet: its group decides. */
function availabilityMessage(practice: CatalogPractice, groups: Catalog["groups"]) {
	if (!hasText(practice.groupSlug)) {
		return;
	}
	const parent = groups.find((group) => group.slug === practice.groupSlug);
	if (!parent) {
		return "Move the practice to an included group first";
	}
	return parent.excluded ? "Practice will be included when its group is included" : undefined;
}

function AdminCatalogPage() {
	const navigate = useNavigate({ from: Route.fullPath });
	const { detail, ...search } = Route.useSearch();
	const queryClient = useQueryClient();
	const detailStack = parseDetailStack(detail, CATALOG_LEVEL_KINDS);
	const stackControls = useDetailStack();
	const pathAt = levelPathAt(detailStack, {
		pageLabel: "Practice catalog",
		labelOf: (entry) => CATALOG_LEVEL_LABELS[entry.kind],
		onClose: stackControls.close,
	});
	const catalogQuery = useQuery({ ...getInstanceCatalogOptions() });

	const invalidateCatalog = () => {
		void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
	};

	const onPracticeStatusSettled = (slug: string, offered: boolean, successMessage?: string) => ({
		onSuccess: (updated: CatalogPractice) => {
			queryClient.setQueryData(detailKey("practice", slug), updated);
			invalidateCatalog();
			toast.success(
				successMessage ??
					(offered ? "Practice included for workspaces" : "Practice excluded from workspaces"),
			);
		},
		onError: (error: unknown) => {
			queryClient.removeQueries({ queryKey: detailKey("practice", slug), exact: true });
			invalidateCatalog();
			toast.error(
				problemStatusOf(error) === 412
					? "The catalog changed before this action was saved. We reloaded the practice."
					: "We could not update the practice",
				{ description: problemDetailOf(error) },
			);
		},
	});
	const onGroupStatusSettled = (slug: string, offered: boolean) => ({
		onSuccess: (catalog: Catalog) => {
			queryClient.setQueryData(getInstanceCatalogQueryKey(), catalog);
			queryClient.removeQueries({ queryKey: detailKey("group", slug), exact: true });
			toast.success(offered ? "Group included for workspaces" : "Group excluded from workspaces");
		},
		onError: (error: unknown) => {
			queryClient.removeQueries({ queryKey: detailKey("group", slug), exact: true });
			invalidateCatalog();
			toast.error(
				problemStatusOf(error) === 412
					? "The catalog changed before this action was saved. We reloaded the group."
					: "We could not update the group",
				{ description: problemDetailOf(error) },
			);
		},
	});

	const updatePracticeStatus = useMutation(
		filedUnder(PRACTICE_STATUS_KEY, setCatalogPracticeStatusMutation()),
	);
	const updateGroupStatus = useMutation(
		filedUnder(GROUP_STATUS_KEY, setCatalogGroupStatusMutation()),
	);
	const reorderGroups = useMutation({
		...reorderCatalogGroupsMutation(),
		scope: STRUCTURE_SCOPE,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: getInstanceCatalogQueryKey() });
			const previous = queryClient.getQueryData<Catalog>(getInstanceCatalogQueryKey());
			if (previous) {
				queryClient.setQueryData(
					getInstanceCatalogQueryKey(),
					reorderCatalogGroups(previous, variables.body.slugs),
				);
			}
			return { previous };
		},
		onSuccess: (catalog) => queryClient.setQueryData(getInstanceCatalogQueryKey(), catalog),
		onError: (error, _variables, context) => {
			if (context?.previous) {
				queryClient.setQueryData(getInstanceCatalogQueryKey(), context.previous);
			}
			structureError(error);
		},
		onSettled: invalidateCatalog,
	});
	const placePractice = useMutation({
		...moveCatalogPracticeMutation(),
		scope: STRUCTURE_SCOPE,
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: getInstanceCatalogQueryKey() });
			const previous = queryClient.getQueryData<Catalog>(getInstanceCatalogQueryKey());
			if (previous) {
				queryClient.setQueryData(
					getInstanceCatalogQueryKey(),
					placeCatalogPractice(
						previous,
						variables.path.slug,
						variables.body.groupSlug,
						variables.body.position,
					),
				);
			}
			return { previous };
		},
		onSuccess: (catalog, variables) => {
			queryClient.setQueryData(getInstanceCatalogQueryKey(), catalog);
			queryClient.removeQueries({
				queryKey: getCatalogPracticeQueryKey({ path: { slug: variables.path.slug } }),
				exact: true,
			});
		},
		onError: (error, _variables, context) => {
			if (context?.previous) {
				queryClient.setQueryData(getInstanceCatalogQueryKey(), context.previous);
			}
			structureError(error);
		},
		onSettled: invalidateCatalog,
	});
	const resetOrder = useMutation({
		...resetCatalogOrderMutation(),
		scope: STRUCTURE_SCOPE,
		onSuccess: (catalog) => {
			queryClient.setQueryData(getInstanceCatalogQueryKey(), catalog);
			toast.success("Hephaestus order restored");
		},
		onError: structureError,
		onSettled: invalidateCatalog,
	});
	const pendingPracticeSlugs = usePendingMutationIds(PRACTICE_STATUS_KEY, (variables) =>
		pathString(variables, "slug"),
	);
	const pendingGroupSlugs = usePendingMutationIds(GROUP_STATUS_KEY, (variables) =>
		pathString(variables, "slug"),
	);
	const structurePending =
		reorderGroups.isPending || placePractice.isPending || resetOrder.isPending;
	const writePending =
		structurePending || pendingGroupSlugs.size > 0 || pendingPracticeSlugs.size > 0;

	let body: ReactNode;
	if (catalogQuery.isPending) {
		body = <PracticeTreeSkeleton groups={3} practicesPerGroup={3} />;
	} else if (catalogQuery.isError) {
		body = (
			<QueryErrorAlert
				error={catalogQuery.error}
				title="We could not load the practice catalog"
				onRetry={() => {
					void catalogQuery.refetch();
				}}
			/>
		);
	} else {
		body = (
			<CatalogPage
				groups={catalogQuery.data.groups}
				practices={catalogQuery.data.practices}
				search={search}
				pendingPracticeSlugs={pendingPracticeSlugs}
				pendingGroupSlugs={pendingGroupSlugs}
				writePending={writePending}
				onSearchChange={(next: CatalogSearch) => {
					void navigate({ search: (previous) => ({ ...previous, ...next }), replace: true });
				}}
				onPracticeStatusChange={(practice: CatalogPractice, offered) => {
					updatePracticeStatus.mutate(
						{
							path: { slug: practice.slug },
							headers: { "If-Match": `"${practice.etag}"` },
							body: { excluded: !offered },
						},
						onPracticeStatusSettled(
							practice.slug,
							offered,
							offered ? availabilityMessage(practice, catalogQuery.data.groups) : undefined,
						),
					);
				}}
				onGroupStatusChange={(group: CatalogGroup, offered) =>
					updateGroupStatus.mutate(
						{
							path: { slug: group.slug },
							headers: { "If-Match": `"${group.etag}"` },
							body: { excluded: !offered },
						},
						onGroupStatusSettled(group.slug, offered),
					)
				}
				onReorderGroups={(slugs) => reorderGroups.mutate({ body: { slugs } })}
				onResetOrder={() => resetOrder.mutate({})}
				onPlacePractice={(practiceSlug, groupSlug, position) =>
					placePractice.mutate({
						path: { slug: practiceSlug },
						body: { groupSlug, position },
					})
				}
			/>
		);
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<LibraryBig />}
				title="Practice catalog"
				description="Choose which groups and practices workspace admins can add. Changes here never rewrite existing workspace practices."
				actions={
					<div className="flex flex-wrap gap-2">
						{writePending ? (
							<Button variant="outline" disabled>
								<Plus className="mr-1.5 size-4" aria-hidden />
								Create group
							</Button>
						) : (
							<DetailStackLink
								entry={catalogGroupLevel()}
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
							<DetailStackLink entry={catalogPracticeLevel()} className={buttonVariants()}>
								<Plus className="mr-1.5 size-4" aria-hidden />
								Create practice
							</DetailStackLink>
						)}
					</div>
				}
			/>

			{body}

			<DetailDrawerStack
				stack={detailStack}
				guardedKinds={GUARDED_CATALOG_LEVEL_KINDS}
				onClose={stackControls.close}
			>
				{(entry, level) => {
					const done = () => stackControls.close(level.depth);
					const levelProps = { nested: level.nested, path: pathAt(level.depth), onDone: done };
					if (entry.kind === "practice-new") {
						return <CatalogPracticeCreateLevel {...levelProps} />;
					}
					if (entry.kind === "practice-edit") {
						return <CatalogPracticeEditLevel practiceSlug={entry.id} {...levelProps} />;
					}
					if (entry.kind === "group-new") {
						return <CatalogGroupCreateLevel {...levelProps} />;
					}
					return <CatalogGroupEditLevel groupSlug={entry.id} {...levelProps} />;
				}}
			</DetailDrawerStack>
		</PageLayout>
	);
}
