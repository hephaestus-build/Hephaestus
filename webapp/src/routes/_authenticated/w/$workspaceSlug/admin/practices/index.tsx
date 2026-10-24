import {
	type QueryKey,
	useMutation,
	useQueries,
	useQuery,
	useQueryClient,
} from "@tanstack/react-query";
import { createFileRoute, Link, retainSearchParams } from "@tanstack/react-router";
import { Inbox, ListChecks } from "lucide-react";
import { type ReactNode, useState } from "react";
import { toast } from "sonner";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import {
	adoptGroupMutation,
	adoptPracticeMutation,
	getPracticeDefinitionOptionsOptions,
	getPracticeOptions,
	listAdoptablePracticesOptions,
	listAdoptablePracticesQueryKey,
	listPracticeGroupsOptions,
	listPracticeGroupsQueryKey,
	listPracticesOptions,
	listPracticesQueryKey,
	listPracticeReleasesOptions,
	listPracticeScriptNeedsOptions,
	listPracticeScriptNeedsQueryKey,
	previewGroupAdoptionOptions,
	previewPracticeAdoptionOptions,
} from "@/api/@tanstack/react-query.gen";
import type {
	CatalogGroupAdoptionPreview,
	CatalogPracticePreview,
	Practice,
	PracticeGroup,
	UpdatePracticeGroupRequest,
	UpdatePracticeRequest,
} from "@/api/types.gen";
import { generateSlug } from "@/components/admin/practice-editor/constants";
import {
	type GroupAdoptionPreview,
	parseAdoptablePractices,
	parseGroupAdoptionPreview,
	parsePracticeAdoptionPreview,
	type PracticeAdoptionPreview,
} from "@/components/admin/practices/catalog-adoption";
import {
	GroupAdoptionPanel,
	type GroupAdoptionState,
} from "@/components/admin/practices/GroupAdoptionPanel";
import {
	DETAIL_LEVEL_KINDS,
	GUARDED_LEVEL_KINDS,
	PRACTICE_SEARCH_PARAMS,
	PRACTICE_SETUP_LEVEL_LABELS,
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
import { ResetToCatalogDialog } from "@/components/admin/practices/ResetToCatalogDialog";
import {
	type PracticeScriptNeedsState,
	WorkspacePracticePanel,
	type WorkspacePracticeState,
} from "@/components/admin/practices/WorkspacePracticePanel";
import { panelState } from "@/components/common/panel-state";
import { QueryErrorAlert, queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import {
	type DetailStackEntry,
	detailStackKey,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";
import {
	type DetailDrawerLevel,
	DetailDrawerStack,
} from "@/components/layout/detail-drawer/DetailDrawerStack";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { refreshPracticeGuidance } from "@/hooks/practice-guidance-cache";
import { usePracticeCatalogMutations } from "@/hooks/use-practice-catalog-mutations";
import { usePracticeCatalogReset } from "@/hooks/use-practice-catalog-reset";
import { usePracticeEditor } from "@/hooks/use-practice-editor";
import { isRecord } from "@/lib/is-record";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemStatusOf } from "@/lib/problem-detail";
import { queryOperationId } from "@/lib/query-operation-id";
import { useSearchState } from "@/lib/search-params";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

type LevelEntry = DetailStackEntry<(typeof DETAIL_LEVEL_KINDS)[number]>;

/** A level's payload, tagged by the kind of entry that produced it; a level not yet created has none. */
type LevelPayload =
	| { kind: "catalog-group"; data: GroupAdoptionPreview }
	| { kind: "catalog-practice"; data: PracticeAdoptionPreview }
	| { kind: "practice"; data: Practice };
type LevelQuery = { data: LevelPayload | null | undefined } | undefined;

/**
 * The read behind one level, keyed by that level's slug. `useQueries` cannot correlate a result with
 * the entry that produced it, so each payload is tagged: without it every read is a union, and a
 * mis-ordered stack is a runtime `undefined.name` rather than a type error.
 */
function levelQueryOptions(workspaceSlug: string, entry: LevelEntry) {
	if (entry.kind === "catalog-group") {
		return {
			...previewGroupAdoptionOptions({ path: { workspaceSlug, slug: entry.id } }),
			select: (data: CatalogGroupAdoptionPreview) =>
				({ kind: "catalog-group", data: parseGroupAdoptionPreview(data) }) as const,
		};
	}
	if (entry.kind === "catalog-practice") {
		return {
			...previewPracticeAdoptionOptions({ path: { workspaceSlug, slug: entry.id } }),
			select: (data: CatalogPracticePreview) =>
				({ kind: "catalog-practice", data: parsePracticeAdoptionPreview(data) }) as const,
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
}

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

function groupDetails(
	group: PracticeGroup,
	patch: Partial<UpdatePracticeGroupRequest>,
): UpdatePracticeGroupRequest {
	const { name, description, icon, color, shownToDevelopers } = group;
	return { name, description, icon, color, shownToDevelopers, ...patch };
}

/** What the adoption panel's primary action reads as, once the preview it acts on is known. */
function adoptionAction(stale: boolean, adding: boolean) {
	if (stale) {
		return "stale";
	}
	return adding ? "adding" : "idle";
}

/** The slice of a query result that decides what a region shows when the read fails. */
interface ReadResult {
	isError: boolean;
	error: unknown;
	isFetching: boolean;
	refetch: () => unknown;
}

/**
 * The failure a region shows for the reads it needs: a refusal before any other, so it is never
 * hidden behind a failure that a retry could clear. Retry repeats only the reads that failed.
 */
function readFailure(reads: readonly (ReadResult | undefined)[]) {
	const failed = reads.filter((read): read is ReadResult => read?.isError === true);
	const shown = failed.find((read) => !queryErrorIsRetryable(read.error)) ?? failed[0];
	if (shown === undefined) {
		return;
	}
	return {
		error: shown.error,
		refused: !queryErrorIsRetryable(shown.error),
		pending: failed.some((read) => read.isFetching),
		retry: () => {
			for (const read of failed) {
				void read.refetch();
			}
		},
	};
}

interface PracticeSetupLevelProps {
	queryKey: QueryKey;
	query: { isError: boolean; error: unknown } | undefined;
	children: ReactNode;
}

/**
 * A drawer level over its own read. Each level the URL opens needs its own component, because the
 * read is one of `useQueries`' results and a hook cannot run inside the stack's render callback.
 */
function PracticeSetupLevel({ queryKey, query, children }: PracticeSetupLevelProps): ReactNode {
	useQueryReadRefusal(queryKey, query?.isError === true, query?.error);
	return children;
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
	const [conflictSlug, setConflictSlug] = useState<string | null>(null);
	const [editorGeneration, setEditorGeneration] = useState(0);
	const catalog = usePracticeCatalogMutations(workspaceSlug);
	const editor = usePracticeEditor(workspaceSlug);
	const queryClient = useQueryClient();

	// Every open level owns its own preview query, keyed by that level's slug. Sharing one query per
	// kind would let `?detail=practice:a&detail=practice:b` show a's definition while adding b.
	const detailStack = parseDetailStack(detail, DETAIL_LEVEL_KINDS);
	const stackControls = useDetailStack();

	const groupsOptions = listPracticeGroupsOptions({ path: { workspaceSlug } });
	const practicesOptions = listPracticesOptions({ path: { workspaceSlug } });
	const definitionOptionsOptions = getPracticeDefinitionOptionsOptions({ path: { workspaceSlug } });
	const groupsQuery = useQuery(groupsOptions);
	const practicesQuery = useQuery(practicesOptions);
	const releasesQuery = useQuery({
		...listPracticeReleasesOptions({ path: { workspaceSlug } }),
	});
	const definitionOptionsQuery = useQuery(definitionOptionsOptions);
	useQueryReadRefusal(groupsOptions.queryKey, groupsQuery.isError, groupsQuery.error);
	useQueryReadRefusal(practicesOptions.queryKey, practicesQuery.isError, practicesQuery.error);
	useQueryReadRefusal(
		definitionOptionsOptions.queryKey,
		definitionOptionsQuery.isError,
		definitionOptionsQuery.error,
	);
	const catalogQuery = useQuery({
		...listAdoptablePracticesOptions({ path: { workspaceSlug } }),
		select: parseAdoptablePractices,
		enabled: library === true,
	});
	const practiceScriptNeedsQuery = useQuery({
		...listPracticeScriptNeedsOptions({ path: { workspaceSlug } }),
		enabled: detailStack.some((entry) => entry.kind === "practice"),
	});
	// Tag query payloads because useQueries cannot correlate a union result with its source entry.
	const levelQueries = useQueries({
		queries: detailStack.map((entry) => levelQueryOptions(workspaceSlug, entry)),
	});

	// A level behind is named by what it shows once that has loaded, and by its kind until then.
	const pathAt = levelPathAt(detailStack, {
		pageLabel: "Practice setup",
		labelOf: (entry, index) => {
			const query = levelQueries[index];
			const label = PRACTICE_SETUP_LEVEL_LABELS[entry.kind];
			if (entry.kind === "catalog-group") {
				return groupAdoptionAt(query)?.definition.name ?? label;
			}
			if (entry.kind === "catalog-practice") {
				return practiceAdoptionAt(query)?.definition.name ?? label;
			}
			if (entry.kind === "practice") {
				const practice = workspacePracticeAt(query);
				return practice === undefined ? label : currentDefinition(practice).name;
			}
			return label;
		},
		onClose: stackControls.close,
	});

	const practiceScriptNeedsOf = (practiceSlug: string): PracticeScriptNeedsState =>
		panelState(practiceScriptNeedsQuery, (summaries) => ({
			status: "ready" as const,
			summary: summaries.find((summary) => summary.practiceSlug === practiceSlug),
		}));

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

	const groups = groupsQuery.data;
	const practices = practicesQuery.data;
	const definitionOptions = definitionOptionsQuery.data;
	/** The groups the editor offers. A hidden group still holds practices but is not a destination. */
	const editableGroups = groupsQuery.data?.filter((group) => group.shownToDevelopers);

	const catalogReset = usePracticeCatalogReset({
		workspaceSlug,
		groups,
		practices,
		mutations: catalog,
	});

	const invalidateCatalogQueries = async () =>
		Promise.all([
			queryClient.invalidateQueries({
				queryKey: listPracticeGroupsQueryKey({ path: { workspaceSlug } }),
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
			await Promise.all([
				invalidateCatalogQueries(),
				...result.added.map(async (practice) =>
					refreshPracticeGuidance(queryClient, workspaceSlug, practice.slug),
				),
			]);
			const changes = [
				result.added.length > 0 &&
					`${result.added.length} ${result.added.length === 1 ? "practice" : "practices"} added`,
				result.moved.length > 0 && `${result.moved.length} moved`,
			].filter(Boolean);
			toast.success("Group updated", { description: changes.join(", ") });
		},
		onError: (error) => {
			if (problemStatusOf(error) !== 412) {
				toast.error("We could not add the group. Nothing was changed. Try again.");
			}
		},
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
			const adopted = await adoptCatalogPractice.mutateAsync({
				path: { workspaceSlug, slug: entry.id },
				headers: { "If-Match": preview.etag },
			});
			stackControls.close(depth);
			await Promise.all([
				invalidateCatalogQueries(),
				refreshPracticeGuidance(queryClient, workspaceSlug, adopted.slug),
			]);
			toast.success("Practice added", { description: preview.definition.name });
		} catch (error) {
			if (problemStatusOf(error) !== 412) {
				toast.error("We could not add the practice. Try again.");
				return;
			}
			const refreshed = await query.refetch();
			const current = practiceAdoptionAt(refreshed);
			if (current?.availability === "ADOPTED") {
				await invalidateCatalogQueries();
				toast.info("This practice is already in the workspace");
				stackControls.close(depth);
			} else if (current) {
				setStaleLevelKey(detailStackKey(entry));
			} else {
				toast.error(
					"The catalog changed, and we could not reload the preview. Reopen the practice, then try again.",
				);
			}
		}
	};

	// A refusal hides the tree even over cached data. Any other failure leaves the loaded tree, and a
	// group being named in it, on screen beside the retry.
	const treeFailure = readFailure([groupsQuery, practicesQuery]);
	let catalogTree: ReactNode;
	if (treeFailure?.refused !== true && groups !== undefined && practices !== undefined) {
		const updateGroup = async (groupSlug: string, patch: Partial<UpdatePracticeGroupRequest>) => {
			const group = groups.find(({ slug }) => slug === groupSlug);
			return group
				? catalog.updateGroup.mutateAsync({
						path: { workspaceSlug, groupSlug },
						body: groupDetails(group, patch),
					})
				: Promise.reject(new Error(`No group ${groupSlug}`));
		};
		catalogTree = (
			<>
				{treeFailure !== undefined && (
					<QueryErrorAlert
						error={treeFailure.error}
						title="We could not refresh practices"
						pending={treeFailure.pending}
						onRetry={treeFailure.retry}
					/>
				)}
				<PracticeCatalog
					workspaceSlug={workspaceSlug}
					groups={groups}
					practices={practices}
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
								// A null icon or colour means "not chosen": the server seeds the chip from the slug.
								body: {
									slug: generateSlug(name),
									name,
									description: null,
									icon,
									color,
									position: null,
									shownToDevelopers: true,
								},
							});
							return true;
						} catch {
							return false;
						}
					}}
					onUpdateGroup={async (groupSlug, details) => {
						try {
							await updateGroup(groupSlug, details);
							return true;
						} catch {
							return false;
						}
					}}
					onSetGroupShownToDevelopers={(groupSlug, shownToDevelopers) => {
						void updateGroup(groupSlug, { shownToDevelopers }).catch(() => undefined);
					}}
					onDeleteGroup={(groupSlug) =>
						setDeletingGroup(groups.find((group) => group.slug === groupSlug) ?? null)
					}
					onResetGroup={(groupSlug) => catalogReset.open({ kind: "group", slug: groupSlug })}
					onReorderGroups={(slugs) =>
						catalog.reorderGroups.mutate({ path: { workspaceSlug }, body: { slugs } })
					}
					onSetGroupVisual={(groupSlug, patch) => {
						void updateGroup(groupSlug, patch).catch(() => undefined);
					}}
					onDeletePractice={setDeletingPractice}
					onMovePractice={(practiceSlug, groupSlug, position) =>
						catalog.movePractice.mutate({
							path: { workspaceSlug, practiceSlug },
							body: { groupSlug, position },
						})
					}
				/>
			</>
		);
	} else if (treeFailure === undefined) {
		catalogTree = <PracticeTreeSkeleton groups={3} practicesPerGroup={3} />;
	} else {
		catalogTree = (
			<QueryErrorAlert
				error={treeFailure.error}
				title="We could not load practices"
				pending={treeFailure.pending}
				onRetry={treeFailure.retry}
			/>
		);
	}

	const renderLevel = (entry: LevelEntry, level: DetailDrawerLevel): ReactNode => {
		// `levelQueries` is derived from the same stack this callback walks, so index and depth
		// agree. A level read before its query exists is a frame between two states rather than a
		// state of its own, and it reads as loading.
		const query = levelQueries[level.depth];
		const levelPending = query === undefined || query.isPending;
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
						toast.error(
							"The catalog changed, and we could not reload the group plan. Reopen the group, then try again.",
						);
					}
				}
			};
			const failure = readFailure([query]);
			let state: GroupAdoptionState;
			if (failure !== undefined) {
				state = { status: "error", error: failure.error, onRetry: failure.retry };
			} else if (groupPreview === undefined || levelPending) {
				state = { status: "loading" };
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
					path={pathAt(level.depth)}
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
			const failure = readFailure([query, definitionOptionsQuery]);
			let state: WorkspacePracticeState;
			if (failure !== undefined) {
				state = { status: "error", error: failure.error, onRetry: failure.retry };
			} else if (
				workspacePractice === undefined ||
				levelPending ||
				definitionOptions === undefined
			) {
				state = { status: "loading" };
			} else {
				state = {
					status: "ready",
					practice: workspacePractice,
					definitionOptions,
					groupName: groups?.find((group) => group.slug === workspacePractice.groupSlug)?.name,
				};
			}
			return (
				<WorkspacePracticePanel
					workspaceSlug={workspaceSlug}
					nested={level.nested}
					path={pathAt(level.depth)}
					state={state}
					practiceScript={practiceScriptNeedsOf(entry.id)}
					onResetToCatalog={() => catalogReset.open({ kind: "practice", slug: entry.id })}
				/>
			);
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
			const conflict =
				!creating && conflictSlug === entry.id && editing !== undefined ? (
					<Alert variant="warning" role="alert">
						<AlertTitle>Someone saved a newer version</AlertTitle>
						<AlertDescription>
							<p>
								Nothing was saved. Your changes are still here, so copy what you want to keep.
								Loading version {editing.currentVersion.number} replaces them.
							</p>
							<Button
								size="sm"
								variant="outline"
								className="mt-2"
								onClick={() => {
									setConflictSlug(null);
									setEditorGeneration((generation) => generation + 1);
								}}
							>
								Load version {editing.currentVersion.number}
							</Button>
						</AlertDescription>
					</Alert>
				) : undefined;
			const update = async (
				practice: Practice,
				request: UpdatePracticeRequest,
				groupSlug: string | null,
			) => {
				try {
					await editor.update(practice, request, groupSlug);
				} catch (error) {
					if (problemStatusOf(error) === 409) {
						setConflictSlug(practice.slug);
						void query?.refetch();
					}
					throw error;
				}
				void queryClient.invalidateQueries({
					queryKey: listPracticeScriptNeedsQueryKey({ path: { workspaceSlug } }),
				});
			};
			// A refusal hides the form even over cached data. Any other failure leaves a loaded draft
			// open beside the retry.
			const failure = readFailure([groupsQuery, definitionOptionsQuery, query]);
			let body: ReactNode;
			if (
				failure?.refused !== true &&
				editableGroups !== undefined &&
				definitionOptions !== undefined &&
				(creating || editing !== undefined)
			) {
				body = (
					// Split on the loaded practice rather than on `creating`: the guard above has already
					// sent every other way of having none elsewhere, so no practice here means this level is
					// writing a new one.
					<PracticeForm
						key={`${entry.id}:${editorGeneration}`}
						{...(editing === undefined
							? {
									mode: "create" as const,
									onSubmit: async (request) => saved(editor.create(request)),
								}
							: {
									mode: "edit" as const,
									initialData: editing,
									notice: conflict,
									onSubmit: async (request, groupSlug) =>
										saved(update(editing, request, groupSlug)),
								})}
						workspaceSlug={workspaceSlug}
						groups={editableGroups}
						definitionOptions={definitionOptions}
						isPending={editor.isPending}
						cancel={<LevelCancel />}
						refreshAlert={
							failure === undefined ? undefined : (
								<QueryErrorAlert
									error={failure.error}
									title="We could not refresh the practice editor"
									pending={failure.pending}
									onRetry={failure.retry}
								/>
							)
						}
					/>
				);
			} else if (failure === undefined) {
				body = (
					<DrawerBody>
						<PracticeDefinitionSkeleton />
					</DrawerBody>
				);
			} else {
				body = (
					<DrawerBody>
						<QueryErrorAlert
							error={failure.error}
							title="We could not load the practice editor"
							pending={failure.pending}
							onRetry={failure.retry}
						/>
					</DrawerBody>
				);
			}
			return (
				<PracticeFormLevel nested={level.nested} path={pathAt(level.depth)} creating={creating}>
					{body}
				</PracticeFormLevel>
			);
		}
		const catalogPreview = practiceAdoptionAt(query);
		const failure = readFailure([query, definitionOptionsQuery]);
		let state: PracticeAdoptionState;
		if (failure !== undefined) {
			state = { status: "error", error: failure.error, onRetry: failure.retry };
		} else if (catalogPreview === undefined || levelPending || definitionOptions === undefined) {
			state = { status: "loading" };
		} else {
			state = {
				status: "ready",
				preview: catalogPreview,
				definitionOptions,
				action: adoptionAction(
					staleLevelKey === detailStackKey(entry),
					adoptCatalogPractice.isPending,
				),
			};
		}
		return (
			<PracticeAdoptionPanel
				nested={level.nested}
				path={pathAt(level.depth)}
				state={state}
				onAdopt={() => {
					void adoptReviewedPractice(level.depth);
				}}
			/>
		);
	};

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
						Organize this workspace’s practices and add suggestions from the instance catalog.
						Whether each practice is reviewed, and how far its reviews go on their own, is set in{" "}
						<Link
							to="/w/$workspaceSlug/admin/practices/review"
							params={{ workspaceSlug }}
							search={{}}
							className="font-medium underline underline-offset-4 hover:text-foreground"
						>
							Review settings
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
				{(entry, level) => (
					<PracticeSetupLevel
						queryKey={levelQueryOptions(workspaceSlug, entry).queryKey}
						query={levelQueries[level.depth]}
					>
						{renderLevel(entry, level)}
					</PracticeSetupLevel>
				)}
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
						<AlertDialogTitle>Delete group “{deletingGroup?.name}”?</AlertDialogTitle>
						<AlertDialogDescription>
							Its practices stay in the workspace, unassigned, with their observations.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							className="min-w-28"
							disabled={catalog.deleteGroup.isPending}
							onClick={() => {
								if (deletingGroup) {
									catalog.deleteGroup.mutate(
										{ path: { workspaceSlug, groupSlug: deletingGroup.slug } },
										{ onSuccess: () => setDeletingGroup(null) },
									);
								}
							}}
						>
							{catalog.deleteGroup.isPending ? "Deleting…" : "Delete group"}
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
						<AlertDialogTitle>
							Delete practice “
							{deletingPractice === null ? "" : currentDefinition(deletingPractice).name}”?
						</AlertDialogTitle>
						<AlertDialogDescription>
							This permanently deletes the practice and its observations. You cannot undo this.
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

			<ResetToCatalogDialog
				open={catalogReset.target !== null}
				onOpenChange={(open) => {
					if (!open) {
						catalogReset.close();
					}
				}}
				name={catalogReset.name}
				state={catalogReset.state}
				pending={catalog.resetPractice.isPending || catalog.resetGroup.isPending}
				stale={catalogReset.stale}
				onConfirm={catalogReset.confirm}
			/>
		</PageLayout>
	);
}
