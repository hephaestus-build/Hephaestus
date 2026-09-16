import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";

import {
	adminDeleteCuratedGroupOverrideMutation,
	adminGetCuratedCatalogQueryKey,
	adminGetCuratedGroupOptions,
	adminGetCuratedGroupQueryKey,
	adminKeepCuratedGroupMutation,
	adminUpdateCuratedGroupMutation,
} from "@/api/@tanstack/react-query.gen";
import type { CuratedGroup } from "@/api/types.gen";
import { CuratedFormLevel } from "@/components/admin/curated-catalog/CuratedFormLevel";
import { CuratedGroupForm } from "@/components/admin/curated-catalog/CuratedGroupForm";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { DrawerBody } from "@/components/ui/drawer";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

export interface CuratedGroupEditLevelProps {
	groupSlug: string;
	nested?: boolean;
	onDone: () => void;
}

export function CuratedGroupEditLevel({ groupSlug, nested, onDone }: CuratedGroupEditLevelProps) {
	const groupQuery = useQuery({ ...adminGetCuratedGroupOptions({ path: { slug: groupSlug } }) });

	return (
		<CuratedFormLevel kind="group-edit" nested={nested}>
			{groupQuery.isPending ? (
				<DrawerBody>
					<PracticeDefinitionSkeleton />
				</DrawerBody>
			) : groupQuery.isError ? (
				<DrawerBody>
					<QueryErrorAlert
						error={groupQuery.error}
						title="Couldn't load the group"
						onRetry={() => void groupQuery.refetch()}
					/>
				</DrawerBody>
			) : (
				<LoadedCuratedGroupEditor
					key={groupSlug}
					groupSlug={groupSlug}
					initialGroup={groupQuery.data}
					onDone={onDone}
				/>
			)}
		</CuratedFormLevel>
	);
}

interface LoadedCuratedGroupEditorProps {
	groupSlug: string;
	initialGroup: CuratedGroup;
	onDone: () => void;
}

function LoadedCuratedGroupEditor({
	groupSlug,
	initialGroup,
	onDone,
}: LoadedCuratedGroupEditorProps) {
	const queryClient = useQueryClient();
	const [baseGroup, setBaseGroup] = useState(initialGroup);
	const [conflict, setConflict] = useState(false);
	const [formGeneration, setFormGeneration] = useState(0);
	const detailOptions = adminGetCuratedGroupOptions({ path: { slug: groupSlug } });
	const detailQueryKey = adminGetCuratedGroupQueryKey({ path: { slug: groupSlug } });

	const continueWithDraft = async () => {
		try {
			await queryClient.invalidateQueries({
				queryKey: detailQueryKey,
				exact: true,
				refetchType: "none",
			});
			const latest: CuratedGroup = await queryClient.query(detailOptions);
			setBaseGroup(latest);
			setConflict(false);
		} catch (error) {
			toast.error("Couldn't load the current version", {
				description: problemDetailOf(error),
			});
		}
	};

	const invalidateCatalog = () =>
		void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });

	const updateGroup = useMutation({
		...adminUpdateCuratedGroupMutation(),
		onSuccess: (updated: CuratedGroup) => {
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
			toast.error("Couldn't update the group", { description: problemDetailOf(error) });
		},
	});
	const deleteOverride = useMutation({
		...adminDeleteCuratedGroupOverrideMutation(),
		onSuccess: (updated: CuratedGroup) => {
			queryClient.setQueryData(detailQueryKey, updated);
			invalidateCatalog();
			setBaseGroup(updated);
			setFormGeneration((generation) => generation + 1);
			setConflict(false);
			toast.success(
				baseGroup.status.state === "UPDATE_WAITING"
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
			toast.error("Couldn't apply the default", { description: problemDetailOf(error) });
		},
	});
	const keepCurrentDefinition = useMutation({
		...adminKeepCuratedGroupMutation(),
		onSuccess: (updated: CuratedGroup) => {
			queryClient.setQueryData(detailQueryKey, updated);
			invalidateCatalog();
			setBaseGroup(updated);
			setConflict(false);
			toast.success(
				baseGroup.status.state === "NO_LONGER_SHIPPED"
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
			toast.error("Couldn't keep the saved version", { description: problemDetailOf(error) });
		},
	});

	return (
		<CuratedGroupForm
			key={`${groupSlug}-${formGeneration}`}
			mode="edit"
			cancel={<LevelCancel />}
			initialData={{
				slug: baseGroup.slug,
				...baseGroup.definition,
				description: baseGroup.definition.description ?? undefined,
				icon: baseGroup.definition.icon ?? undefined,
				color: baseGroup.definition.color ?? undefined,
				status: baseGroup.status,
				shipped: baseGroup.shipped,
			}}
			isPending={updateGroup.isPending}
			isResetPending={deleteOverride.isPending}
			isKeepPending={keepCurrentDefinition.isPending}
			conflict={conflict}
			onContinueWithDraft={() => void continueWithDraft()}
			onUseHephaestusVersion={() => {
				setConflict(false);
				deleteOverride.mutate({
					path: { slug: groupSlug },
					headers: { "If-Match": `"${baseGroup.status.etag}"` },
				});
			}}
			onKeepCurrentDefinition={() => {
				setConflict(false);
				keepCurrentDefinition.mutate({
					path: { slug: groupSlug },
					headers: { "If-Match": `"${baseGroup.status.etag}"` },
				});
			}}
			onSubmit={({ slug: _slug, ...definition }) => {
				setConflict(false);
				updateGroup.mutate({
					path: { slug: groupSlug },
					headers: { "If-Match": `"${baseGroup.status.etag}"` },
					body: definition,
				});
			}}
		/>
	);
}
