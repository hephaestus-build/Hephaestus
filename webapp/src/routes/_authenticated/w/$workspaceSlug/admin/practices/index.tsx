import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, retainSearchParams } from "@tanstack/react-router";
import { Inbox, ListChecks } from "lucide-react";
import { type ReactNode, useState } from "react";
import { toast } from "sonner";

import {
	adoptGroupMutation,
	adoptPracticeMutation,
	getPracticeDefinitionOptionsOptions,
	getPracticeOptions,
	listAdoptablePracticesOptions,
	listAdoptablePracticesQueryKey,
	listGroupsOptions,
	listGroupsQueryKey,
	listPracticeEvidenceOutcomesOptions,
	listPracticesOptions,
	listPracticesQueryKey,
	listPracticeReleasesOptions,
	previewGroupAdoptionOptions,
	previewPracticeAdoptionOptions,
} from "@/api/@tanstack/react-query.gen";
import type {
	CatalogGroupAdoptionPreview,
	CatalogPracticePreview,
	Practice,
	PracticeGroup,
} from "@/api/types.gen";
import { generateSlug } from "@/components/admin/practice-editor/constants";
import {
	GroupAdoptionPanel,
	type GroupAdoptionState,
} from "@/components/admin/practices/GroupAdoptionPanel";
import {
	DETAIL_LEVEL_KINDS,
	GUARDED_LEVEL_KINDS,
	PRACTICE_SEARCH_PARAMS,
	practiceSetupSearchSchema,
} from "@/components/admin/practices/practice-search";
import {
	PracticeAdoptionPanel,
	type PracticeAdoptionState,
} from "@/components/admin/practices/PracticeAdoptionPanel";
import {
	type FocusFilter,
	type LibraryState,
	PracticeCatalog,
} from "@/components/admin/practices/PracticeCatalog";
import { PracticeForm } from "@/components/admin/practices/PracticeForm";
import { PracticeFormLevel } from "@/components/admin/practices/PracticeFormLevel";
import {
	PracticeDefinitionSkeleton,
	PracticeTreeSkeleton,
} from "@/components/admin/practices/PracticeSkeletons";
import {
	WorkspacePracticePanel,
	type WorkspacePracticeState,
} from "@/components/admin/practices/WorkspacePracticePanel";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { detailStackKey, parseDetailStack } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { DrawerBody } from "@/components/ui/drawer";
import { practiceCatalogStructureScope } from "@/hooks/practice-catalog-cache";
import { usePracticeCatalogMutations } from "@/hooks/use-practice-catalog-mutations";
import { usePracticeEditor } from "@/hooks/use-practice-editor";
import { isRecord } from "@/lib/is-record";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemStatusOf } from "@/lib/problem-detail";
import { queryOperationId } from "@/lib/query-operation-id";
import { useSearchState } from "@/lib/search-params";

/** A level's payload, tagged by the kind of entry that produced it; a level not yet created has none. */
type LevelPayload =
	| { kind: "catalog-group"; data: CatalogGroupAdoptionPreview }
	| { kind: "catalog-practice"; data: CatalogPracticePreview }
	| { kind: "practice"; data: Practice };
type LevelQuery = { data: LevelPayload | null | undefined } | undefined;

/**
 * Reads a level's payload only when it is the kind the caller is rendering. One reader per kind
 * rather than one that takes the kind: a `===` against a value typed by a type parameter narrows
 * nothing, so a shared reader would have to assert back what the tag already proves.
 */
function groupAdoptionAt(query: LevelQuery) {
	const tagged = query?.data;
	return tagged?.kind === "catalog-group" ? tagged.data : undefined;
}
function practiceAdoptionAt(query: LevelQuery) {
	const tagged = query?.data;
	return tagged?.kind === "catalog-practice" ? tagged.data : undefined;
}
function workspacePracticeAt(query: LevelQuery) {
	const tagged = query?.data;
	return tagged?.kind === "practice" ? tagged.data : undefined;
}

/** What the adoption panel's primary action reads as, once the preview it acts on is known. */
function adoptionAction(stale: boolean, adding: boolean) {
	if (stale) {
		return "stale";
	}
	return adding ? "adding" : "idle";
}

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/")({
	head: workspaceAdminHead("Practices"),
	validateSearch: practiceSetupSearchSchema,
	search: { middlewares: [retainSearchParams(PRACTICE_SEARCH_PARAMS)] },
	component: PracticeCatalogRoute,
});

function PracticeCatalogRoute() {
	const { workspaceSlug } = Route.useParams();
	const { focus, library, detail } = Route.useSearch();
	const setSearch = useSearchState();

	const [deletingGroup, setDeletingGroup] = useState<PracticeGroup | null>(null);
	const [deletingPractice, setDeletingPractice] = useState<Practice | null>(null);
	const [staleLevelKey, setStaleLevelKey] = useState<string | null>(null);
	const catalog = usePracticeCatalogMutations(workspaceSlug);
	const editor = usePracticeEditor(workspaceSlug);
	const queryClient = useQueryClient();

	// Every open level owns its own preview query, keyed by that level's slug. Sharing one query per
	// kind would let `?detail=practice:a&detail=practice:b` show a's definition while adding b.
	const detailStack = parseDetailStack(detail, DETAIL_LEVEL_KINDS);
	const stackControls = useDetailStack(detailStack);

	const groupsQuery = useQuery({
		...listGroupsOptions({ path: { workspaceSlug } }),
	});
	const practicesQuery = useQuery({
		...listPracticesOptions({ path: { workspaceSlug } }),
	});
	const releasesQuery = useQuery({
		...listPracticeReleasesOptions({ path: { workspaceSlug } }),
	});
	const definitionOptionsQuery = useQuery({
		...getPracticeDefinitionOptionsOptions({ path: { workspaceSlug } }),
	});
	// How past reviews turned out is context beside the form, not something the editor waits for.
	const evidenceOutcomesQuery = useQuery({
		...listPracticeEvidenceOutcomesOptions({ path: { workspaceSlug } }),
	});
	const catalogQuery = useQuery({
		...listAdoptablePracticesOptions({ path: { workspaceSlug } }),
		enabled: library === true,
	});
	// `useQueries` cannot correlate a result with the entry that produced it, so each payload is
	// tagged: without it every read is a union, and a mis-ordered stack is a runtime `undefined.name`
	// rather than a type error.
	const levelQueries = useQueries({
		queries: detailStack.map((entry) => {
			if (entry.kind === "catalog-group") {
				return {
					...previewGroupAdoptionOptions({ path: { workspaceSlug, slug: entry.id } }),
					select: (data: CatalogGroupAdoptionPreview) => ({ kind: "catalog-group", data }) as const,
				};
			}
			if (entry.kind === "catalog-practice") {
				return {
					...previewPracticeAdoptionOptions({ path: { workspaceSlug, slug: entry.id } }),
					select: (data: CatalogPracticePreview) => ({ kind: "catalog-practice", data }) as const,
				};
			}
			if (entry.kind === "practice-new") {
				// oxlint-disable-next-line hephaestus/no-manual-query-key -- A practice that does not exist yet has nothing on the server to key against; this placeholder only keeps `levelQueries` the same length as the stack.
				return { queryKey: ["practice-new", entry.id], queryFn: () => null, staleTime: Infinity };
			}
			return {
				...getPracticeOptions({ path: { workspaceSlug, practiceSlug: entry.id } }),
				select: (data: Practice) => ({ kind: "practice", data }) as const,
			};
		}),
	});

	let libraryState: LibraryState = { status: "loading" };
	if (catalogQuery.isError) {
		libraryState = {
			status: "error",
			error: catalogQuery.error,
			onRetry: () => {
				void catalogQuery.refetch();
			},
		};
	} else if (catalogQuery.data) {
		libraryState = { status: "ready", practices: catalogQuery.data };
	}

	/** The groups the editor offers. A hidden group still holds practices but is not a destination. */
	const editableGroups = groupsQuery.data?.filter((group) => group.visibleInPracticeDashboards);

	const invalidateCatalogQueries = async () =>
		Promise.all([
			queryClient.invalidateQueries({
				queryKey: listGroupsQueryKey({ path: { workspaceSlug } }),
			}),
			queryClient.invalidateQueries({
				queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
			}),
			queryClient.invalidateQueries({
				queryKey: listAdoptablePracticesQueryKey({ path: { workspaceSlug } }),
			}),
			queryClient.invalidateQueries({
				predicate: ({ queryKey }) => {
					if (queryOperationId(queryKey) !== "previewGroupAdoption") {
						return false;
					}
					const [key] = queryKey;
					return isRecord(key) && isRecord(key.path) && key.path.workspaceSlug === workspaceSlug;
				},
			}),
		]);
	const adoptCatalogGroup = useMutation({
		...adoptGroupMutation(),
		onSuccess: async (result) => {
			stackControls.close(0);
			await invalidateCatalogQueries();
			const changes = [
				result.added.length > 0 && `${result.added.length} added`,
				result.moved.length > 0 && `${result.moved.length} moved`,
			].filter(Boolean);
			toast.success("Group updated", { description: changes.join(", ") });
		},
		onError: () => toast.error("Couldn't add the group. Nothing was changed."),
	});
	const adoptCatalogPractice = useMutation({
		...adoptPracticeMutation(),
		scope: practiceCatalogStructureScope(workspaceSlug),
	});

	// Closes only the practice level, so the reader lands back in the catalog they were working through.
	const adoptReviewedPractice = async (depth: number) => {
		const entry = detailStack[depth];
		const query = levelQueries[depth];
		const preview = practiceAdoptionAt(query);
		if (!entry || !query || !preview) {
			return;
		}
		setStaleLevelKey(null);
		try {
			await adoptCatalogPractice.mutateAsync({
				path: { workspaceSlug, slug: entry.id },
				headers: { "If-Match": preview.etag },
			});
			stackControls.close(depth);
			await invalidateCatalogQueries();
			toast.success("Practice added", { description: preview.definition.name });
		} catch (error) {
			const status = problemStatusOf(error);
			if (status === 409) {
				await invalidateCatalogQueries();
				toast.info("This practice is already in the workspace");
				stackControls.close(depth);
				return;
			}
			if (status === 412) {
				const refreshed = await query.refetch();
				if (refreshed.isSuccess) {
					setStaleLevelKey(detailStackKey(entry));
				} else {
					toast.error("The adoption preview changed but couldn't be refreshed");
				}
				return;
			}
			toast.error("Couldn't add the practice");
		}
	};

	let catalogTree: ReactNode;
	if (groupsQuery.isPending || practicesQuery.isPending || definitionOptionsQuery.isPending) {
		catalogTree = <PracticeTreeSkeleton groups={3} practicesPerGroup={3} />;
	} else if (groupsQuery.isError || practicesQuery.isError || definitionOptionsQuery.isError) {
		catalogTree = (
			<QueryErrorAlert
				error={groupsQuery.error ?? practicesQuery.error ?? definitionOptionsQuery.error}
				title="Couldn't load practices"
				onRetry={() => {
					void groupsQuery.refetch();
					void practicesQuery.refetch();
					void definitionOptionsQuery.refetch();
				}}
			/>
		);
	} else {
		catalogTree = (
			<PracticeCatalog
				workspaceSlug={workspaceSlug}
				groups={groupsQuery.data}
				practices={practicesQuery.data}
				definitionOptions={definitionOptionsQuery.data}
				pending={{
					groupSlugs: catalog.pendingGroupSlugs,
					practiceSlugs: catalog.pendingPracticeSlugs,
					groupStructure: catalog.groupStructurePending,
					blockedMoveDestinationSlugs: catalog.blockedMoveDestinationSlugs,
					blockedPracticeOrderBuckets: catalog.blockedPracticeOrderBuckets,
					creatingGroup: catalog.createGroup.isPending,
				}}
				focusFilter={focus ?? "ALL"}
				library={{
					open: library === true,
					onOpenChange: (open) => {
						void setSearch((previous) => ({ ...previous, library: open || undefined }));
					},
					state: libraryState,
				}}
				onFocusFilterChange={(next: FocusFilter) => {
					void setSearch((previous) => ({
						...previous,
						focus: next === "ALL" ? undefined : next,
					}));
				}}
				onCreateGroup={async ({ name, icon, color }) => {
					try {
						await catalog.createGroup.mutateAsync({
							path: { workspaceSlug },
							// The picker only ever sets a value, so `null` means "not chosen" — omit it and the
							// server keeps seeding the chip from the slug.
							body: {
								slug: generateSlug(name),
								name,
								icon: icon ?? undefined,
								color: color ?? undefined,
							},
						});
						return true;
					} catch {
						return false;
					}
				}}
				onUpdateGroup={async (groupSlug, { name, icon, color }) => {
					try {
						await catalog.updateGroup.mutateAsync({
							path: { workspaceSlug, groupSlug },
							body: { name, icon: icon ?? undefined, color: color ?? undefined },
						});
						return true;
					} catch {
						return false;
					}
				}}
				onSetGroupDashboardVisibility={(groupSlug, visibleInPracticeDashboards) =>
					catalog.updateGroup.mutate({
						path: { workspaceSlug, groupSlug },
						body: { visibleInPracticeDashboards },
					})
				}
				onDeleteGroup={(groupSlug) =>
					setDeletingGroup(groupsQuery.data.find((group) => group.slug === groupSlug) ?? null)
				}
				onReorderGroups={(orderedSlugs) =>
					catalog.reorderGroups.mutate({ path: { workspaceSlug }, body: { orderedSlugs } })
				}
				onSetGroupVisual={(groupSlug, patch) =>
					catalog.updateGroup.mutate({ path: { workspaceSlug, groupSlug }, body: patch })
				}
				onDeletePractice={setDeletingPractice}
				onPlacePractice={(practiceSlug, groupSlug, position) =>
					catalog.placePractice.mutate({
						path: { workspaceSlug, practiceSlug },
						body: { groupSlug: groupSlug ?? undefined, position },
					})
				}
			/>
		);
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<ListChecks />}
				title="Practice setup"
				actions={
					<Button
						variant="outline"
						nativeButton={false}
						render={
							<Link to="/w/$workspaceSlug/admin/practices/releases" params={{ workspaceSlug }} />
						}
					>
						<Inbox /> Review updates{releasesQuery.data ? ` (${releasesQuery.data.length})` : ""}
					</Button>
				}
				description={
					<>
						Organize this workspace’s practices and add suggestions from the instance catalog. The
						autonomy — whether each practice is reviewed, and how far its reviews go on their own —
						is set on{" "}
						<Link
							to="/w/$workspaceSlug/admin/practices/review"
							params={{ workspaceSlug }}
							search={{}}
							className="font-medium underline underline-offset-4 hover:text-foreground"
						>
							Review
						</Link>
						.
					</>
				}
			/>
			{catalogTree}

			<DetailDrawerStack
				stack={detailStack}
				guardedKinds={GUARDED_LEVEL_KINDS}
				onClose={stackControls.close}
			>
				{(entry, level) => {
					// `levelQueries` is derived from the same stack this callback walks, so index and depth
					// agree. A level read before its query exists is a frame between two states rather than a
					// state of its own, and it reads as loading.
					const query = levelQueries[level.depth];
					const levelPending = query === undefined || query.isPending;
					const levelError = query?.isError === true ? query.error : undefined;
					const refetchLevel = () => {
						void query?.refetch();
					};
					if (entry.kind === "catalog-group") {
						const groupPreview = groupAdoptionAt(query);
						const adoptGroup = async () => {
							const preview = groupAdoptionAt(query);
							if (!query || !preview) {
								return;
							}
							setStaleLevelKey(null);
							try {
								await adoptCatalogGroup.mutateAsync({
									path: { workspaceSlug, slug: entry.id },
									headers: { "If-Match": preview.etag },
								});
							} catch (error) {
								// Same failure as a practice's, so the same recovery: refresh in place.
								if (problemStatusOf(error) !== 412) {
									return;
								}
								const refreshed = await query.refetch();
								if (refreshed.isSuccess) {
									setStaleLevelKey(detailStackKey(entry));
								} else {
									toast.error("The group plan changed but couldn't be refreshed");
								}
							}
						};
						let state: GroupAdoptionState;
						if (groupPreview === undefined || levelPending) {
							state = { status: "loading" };
						} else if (levelError) {
							state = { status: "error", error: levelError, onRetry: refetchLevel };
						} else {
							state = {
								status: "ready",
								preview: groupPreview,
								action: adoptionAction(
									staleLevelKey === detailStackKey(entry),
									adoptCatalogGroup.isPending,
								),
							};
						}
						return (
							<GroupAdoptionPanel
								nested={level.nested}
								state={state}
								onOpenPractice={(catalogSlug) =>
									stackControls.open({ kind: "catalog-practice", id: catalogSlug })
								}
								onConfirm={() => {
									void adoptGroup();
								}}
							/>
						);
					}
					if (entry.kind === "practice") {
						const workspacePractice = workspacePracticeAt(query);
						let state: WorkspacePracticeState;
						if (
							workspacePractice === undefined ||
							levelPending ||
							definitionOptionsQuery.isPending
						) {
							state = { status: "loading" };
						} else if (levelError !== undefined || definitionOptionsQuery.isError) {
							state = {
								status: "error",
								error: levelError ?? definitionOptionsQuery.error,
								onRetry: () => {
									refetchLevel();
									void definitionOptionsQuery.refetch();
								},
							};
						} else {
							state = {
								status: "ready",
								practice: workspacePractice,
								definitionOptions: definitionOptionsQuery.data,
								groupName: groupsQuery.data?.find(
									(group) => group.slug === workspacePractice.groupSlug,
								)?.name,
							};
						}
						return <WorkspacePracticePanel nested={level.nested} state={state} />;
					}
					if (entry.kind === "practice-edit" || entry.kind === "practice-new") {
						const creating = entry.kind === "practice-new";
						const editing = creating ? undefined : workspacePracticeAt(query);
						// The form saves and then leaves. It must reject on failure, or the unsaved-changes
						// guard lifts and the draft goes with the level.
						const saved = async (work: Promise<unknown>) => {
							await work;
							stackControls.close(level.depth);
						};
						return (
							<PracticeFormLevel nested={level.nested} creating={creating}>
								{editableGroups === undefined ||
								definitionOptionsQuery.data === undefined ||
								(!creating && editing === undefined) ? (
									<DrawerBody>
										<PracticeDefinitionSkeleton />
									</DrawerBody>
								) : (
									// Split on the loaded practice rather than on `creating`: the guard above has
									// already sent every other way of having none to the skeleton, so no practice
									// here means this level is writing a new one.
									<PracticeForm
										{...(editing === undefined
											? {
													mode: "create" as const,
													onSubmit: async (data, groupSlug) =>
														saved(editor.create(data, groupSlug)),
												}
											: {
													mode: "edit" as const,
													initialData: editing,
													onSubmit: async (slug, data, groupSlug) =>
														saved(editor.update(slug, data, groupSlug)),
													evidenceOutcome: evidenceOutcomesQuery.data?.find(
														(outcome) => outcome.practiceSlug === entry.id,
													),
												})}
										workspaceSlug={workspaceSlug}
										groups={editableGroups}
										definitionOptions={definitionOptionsQuery.data}
										isPending={editor.isPending}
										cancel={<LevelCancel />}
									/>
								)}
							</PracticeFormLevel>
						);
					}
					const catalogPreview = practiceAdoptionAt(query);
					let state: PracticeAdoptionState;
					if (catalogPreview === undefined || levelPending || definitionOptionsQuery.isPending) {
						state = { status: "loading" };
					} else if (levelError !== undefined || definitionOptionsQuery.isError) {
						state = {
							status: "error",
							error: levelError ?? definitionOptionsQuery.error,
							onRetry: () => {
								refetchLevel();
								void definitionOptionsQuery.refetch();
							},
						};
					} else {
						state = {
							status: "ready",
							preview: catalogPreview,
							definitionOptions: definitionOptionsQuery.data,
							action: adoptionAction(
								staleLevelKey === detailStackKey(entry),
								adoptCatalogPractice.isPending,
							),
						};
					}
					return (
						<PracticeAdoptionPanel
							nested={level.nested}
							state={state}
							onAdopt={() => {
								void adoptReviewedPractice(level.depth);
							}}
						/>
					);
				}}
			</DetailDrawerStack>

			<AlertDialog
				open={deletingGroup !== null}
				onOpenChange={(open) => {
					if (!open) {
						setDeletingGroup(null);
					}
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Delete “{deletingGroup?.name}”?</AlertDialogTitle>
						<AlertDialogDescription>
							Choose whether to keep this group’s practices in the workspace or delete them with the
							group. Deleting practices also permanently deletes their observations.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter className="sm:grid sm:grid-cols-3">
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="outline"
							disabled={catalog.deleteGroup.isPending}
							onClick={() => {
								if (!deletingGroup) {
									return;
								}
								catalog.deleteGroup.mutate(
									{ path: { workspaceSlug, groupSlug: deletingGroup.slug } },
									{ onSuccess: () => setDeletingGroup(null) },
								);
							}}
						>
							{catalog.deleteGroup.isPending ? "Deleting…" : "Keep practices unassigned"}
						</AlertDialogAction>
						<AlertDialogAction
							variant="destructive"
							disabled={catalog.deleteGroup.isPending}
							onClick={() => {
								if (!deletingGroup) {
									return;
								}
								catalog.deleteGroup.mutate(
									{
										path: { workspaceSlug, groupSlug: deletingGroup.slug },
										query: { deletePractices: true },
									},
									{ onSuccess: () => setDeletingGroup(null) },
								);
							}}
						>
							{catalog.deleteGroup.isPending ? "Deleting…" : "Delete group and practices"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>

			<AlertDialog
				open={deletingPractice !== null}
				onOpenChange={(open) => {
					if (!open) {
						setDeletingPractice(null);
					}
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Delete &ldquo;{deletingPractice?.name}&rdquo;?</AlertDialogTitle>
						<AlertDialogDescription>
							This permanently deletes the practice and its observations. This can’t be undone.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							className="min-w-28"
							onClick={() => {
								if (deletingPractice) {
									catalog.deletePractice.mutate(
										{
											path: { workspaceSlug, practiceSlug: deletingPractice.slug },
										},
										{ onSuccess: () => setDeletingPractice(null) },
									);
								}
							}}
							disabled={catalog.deletePractice.isPending}
						>
							{catalog.deletePractice.isPending ? "Deleting…" : "Delete practice"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</PageLayout>
	);
}
