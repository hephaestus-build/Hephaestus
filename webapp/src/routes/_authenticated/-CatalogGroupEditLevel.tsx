import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import { toast } from "@/lib/toast";

import {
	acknowledgeCatalogGroupMutation,
	getCatalogGroupOptions,
	getCatalogGroupQueryKey,
	getInstanceCatalogQueryKey,
	resetCatalogGroupMutation,
	updateCatalogGroupMutation,
} from "@/api/@tanstack/react-query.gen";
import type { CatalogGroup } from "@/api/types.gen";
import { CatalogFormLevel } from "@/components/admin/catalog/CatalogFormLevel";
import { CatalogGroupForm } from "@/components/admin/catalog/CatalogGroupForm";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert, queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { DrawerBody } from "@/components/ui/drawer";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export interface CatalogGroupEditLevelProps {
	groupSlug: string;
	nested?: boolean;
	path: LevelPath;
	onDone: () => void;
}

export function CatalogGroupEditLevel({
	groupSlug,
	nested,
	path,
	onDone,
}: CatalogGroupEditLevelProps) {
	const groupOptions = getCatalogGroupOptions({ path: { slug: groupSlug } });
	const groupQuery = useQuery(groupOptions);
	useQueryReadRefusal(groupOptions.queryKey, groupQuery.isError, groupQuery.error);
	const retry = () => {
		void groupQuery.refetch();
	};

	let body: ReactNode;
	// Denied reads hide cached drafts; transient refresh failures preserve them.
	if (
		groupQuery.data !== undefined &&
		(!groupQuery.isError || queryErrorIsRetryable(groupQuery.error))
	) {
		body = (
			<LoadedCatalogGroupEditor
				key={groupSlug}
				groupSlug={groupSlug}
				initialGroup={groupQuery.data}
				refreshAlert={
					groupQuery.isError ? (
						<QueryErrorAlert
							error={groupQuery.error}
							title="We could not refresh the group"
							pending={groupQuery.isFetching}
							onRetry={retry}
						/>
					) : undefined
				}
				onDone={onDone}
			/>
		);
	} else if (groupQuery.isError) {
		body = (
			<DrawerBody>
				<QueryErrorAlert
					error={groupQuery.error}
					title="We could not load the group"
					onRetry={retry}
				/>
			</DrawerBody>
		);
	} else {
		body = (
			<DrawerBody>
				<PracticeDefinitionSkeleton />
			</DrawerBody>
		);
	}

	return (
		<CatalogFormLevel kind="group-edit" nested={nested} path={path}>
			{body}
		</CatalogFormLevel>
	);
}

interface LoadedCatalogGroupEditorProps {
	groupSlug: string;
	initialGroup: CatalogGroup;
	refreshAlert: ReactNode;
	onDone: () => void;
}

function LoadedCatalogGroupEditor({
	groupSlug,
	initialGroup,
	refreshAlert,
	onDone,
}: LoadedCatalogGroupEditorProps) {
	const queryClient = useQueryClient();
	const [baseGroup, setBaseGroup] = useState(initialGroup);
	const [conflict, setConflict] = useState(false);
	const [formGeneration, setFormGeneration] = useState(0);
	const detailOptions = getCatalogGroupOptions({ path: { slug: groupSlug } });
	const detailQueryKey = getCatalogGroupQueryKey({ path: { slug: groupSlug } });

	const continueWithDraft = async () => {
		try {
			await queryClient.invalidateQueries({
				queryKey: detailQueryKey,
				exact: true,
				refetchType: "none",
			});
			const latest: CatalogGroup = await queryClient.query({ ...detailOptions, retry: false });
			setBaseGroup(latest);
			setConflict(false);
		} catch (error) {
			toast.error("We could not load the current version", {
				description: problemDetailOf(error),
			});
		}
	};

	const invalidateCatalog = () => {
		void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
	};

	const updateGroup = useMutation({
		...updateCatalogGroupMutation(),
		onSuccess: (updated: CatalogGroup) => {
			queryClient.setQueryData(detailQueryKey, updated);
			invalidateCatalog();
			toast.success("Group updated");
			onDone();
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				setConflict(true);
				return;
			}
			toast.error("We could not update the group", { description: problemDetailOf(error) });
		},
	});
	const resetGroup = useMutation({
		...resetCatalogGroupMutation(),
		onSuccess: (updated: CatalogGroup) => {
			queryClient.setQueryData(detailQueryKey, updated);
			invalidateCatalog();
			setBaseGroup(updated);
			setFormGeneration((generation) => generation + 1);
			setConflict(false);
			toast.success(
				baseGroup.state === "UPDATE_WAITING"
					? "Hephaestus update applied"
					: "Hephaestus default restored",
			);
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				toast.error(
					"The catalog changed before this action was saved. Reopen the group to see the latest version.",
				);
				void queryClient.invalidateQueries({ queryKey: detailQueryKey });
				invalidateCatalog();
				return;
			}
			toast.error("We could not apply the Hephaestus version", {
				description: problemDetailOf(error),
			});
		},
	});
	const keepCurrentDefinition = useMutation({
		...acknowledgeCatalogGroupMutation(),
		onSuccess: (updated: CatalogGroup) => {
			queryClient.setQueryData(detailQueryKey, updated);
			invalidateCatalog();
			setBaseGroup(updated);
			setConflict(false);
			toast.success(
				baseGroup.state === "NO_LONGER_SHIPPED"
					? "Saved group is now custom"
					: "Saved version kept",
			);
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				toast.error(
					"The catalog changed before this action was saved. Reopen the group to see the latest version.",
				);
				void queryClient.invalidateQueries({ queryKey: detailQueryKey });
				invalidateCatalog();
				return;
			}
			toast.error("We could not keep the saved version", { description: problemDetailOf(error) });
		},
	});

	return (
		<CatalogGroupForm
			key={`${groupSlug}-${formGeneration}`}
			mode="edit"
			cancel={<LevelCancel />}
			group={baseGroup}
			isPending={updateGroup.isPending}
			isResetPending={resetGroup.isPending}
			isKeepPending={keepCurrentDefinition.isPending}
			refreshAlert={refreshAlert}
			conflict={conflict}
			onContinueWithDraft={() => {
				void continueWithDraft();
			}}
			onUseHephaestusVersion={() => {
				setConflict(false);
				resetGroup.mutate({
					path: { slug: groupSlug },
					headers: { "If-Match": `"${baseGroup.etag}"` },
				});
			}}
			onKeepCurrentDefinition={() => {
				setConflict(false);
				keepCurrentDefinition.mutate({
					path: { slug: groupSlug },
					headers: { "If-Match": `"${baseGroup.etag}"` },
				});
			}}
			onSubmit={({ slug: _slug, ...group }) => {
				setConflict(false);
				updateGroup.mutate({
					path: { slug: groupSlug },
					headers: { "If-Match": `"${baseGroup.etag}"` },
					body: { ...group, position: null },
				});
			}}
		/>
	);
}
