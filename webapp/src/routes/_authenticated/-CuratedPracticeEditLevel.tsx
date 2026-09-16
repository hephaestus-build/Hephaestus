import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";

import {
	adminDeleteCuratedPracticeOverrideMutation,
	adminGetCuratedCatalogOptions,
	adminGetCuratedCatalogQueryKey,
	adminGetCuratedPracticeOptions,
	adminGetCuratedPracticeQueryKey,
	adminGetPracticeDefinitionOptionsOptions,
	adminKeepCuratedPracticeMutation,
	adminUpdateCuratedPracticeMutation,
} from "@/api/@tanstack/react-query.gen";
import type { CuratedGroup, CuratedPractice, PracticeDefinitionOptions } from "@/api/types.gen";
import { CuratedFormLevel } from "@/components/admin/curated-catalog/CuratedFormLevel";
import {
	CuratedPracticeForm,
	type CuratedPracticeFormValue,
} from "@/components/admin/curated-catalog/CuratedPracticeForm";
import { soleBinding } from "@/components/admin/practice-catalog/bindings";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { DrawerBody } from "@/components/ui/drawer";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

export interface CuratedPracticeEditLevelProps {
	practiceSlug: string;
	nested?: boolean;
	onDone: () => void;
}

export function CuratedPracticeEditLevel({
	practiceSlug,
	nested,
	onDone,
}: CuratedPracticeEditLevelProps) {
	const practiceQuery = useQuery({
		...adminGetCuratedPracticeOptions({ path: { slug: practiceSlug } }),
	});
	const catalogQuery = useQuery({ ...adminGetCuratedCatalogOptions() });
	const definitionOptionsQuery = useQuery({ ...adminGetPracticeDefinitionOptionsOptions() });

	return (
		<CuratedFormLevel kind="practice-edit" nested={nested}>
			{practiceQuery.isPending || catalogQuery.isPending || definitionOptionsQuery.isPending ? (
				<DrawerBody>
					<PracticeDefinitionSkeleton />
				</DrawerBody>
			) : practiceQuery.isError || catalogQuery.isError || definitionOptionsQuery.isError ? (
				<DrawerBody>
					<QueryErrorAlert
						error={practiceQuery.error ?? catalogQuery.error ?? definitionOptionsQuery.error}
						title="Couldn't load the practice"
						onRetry={() => {
							void practiceQuery.refetch();
							void catalogQuery.refetch();
							void definitionOptionsQuery.refetch();
						}}
					/>
				</DrawerBody>
			) : (
				<LoadedCuratedPracticeEditor
					key={practiceSlug}
					practiceSlug={practiceSlug}
					initialPractice={practiceQuery.data}
					groups={catalogQuery.data.groups}
					definitionOptions={definitionOptionsQuery.data}
					onDone={onDone}
				/>
			)}
		</CuratedFormLevel>
	);
}

interface LoadedCuratedPracticeEditorProps {
	practiceSlug: string;
	initialPractice: CuratedPractice;
	groups: CuratedGroup[];
	definitionOptions: PracticeDefinitionOptions;
	onDone: () => void;
}

function LoadedCuratedPracticeEditor({
	practiceSlug,
	initialPractice,
	groups,
	definitionOptions,
	onDone,
}: LoadedCuratedPracticeEditorProps) {
	const queryClient = useQueryClient();
	const [basePractice, setBasePractice] = useState(initialPractice);
	const [conflict, setConflict] = useState(false);
	const [formGeneration, setFormGeneration] = useState(0);
	const detailOptions = adminGetCuratedPracticeOptions({ path: { slug: practiceSlug } });
	const detailQueryKey = adminGetCuratedPracticeQueryKey({ path: { slug: practiceSlug } });
	const updatePractice = useMutation({
		...adminUpdateCuratedPracticeMutation(),
		onSuccess: (updated) => {
			queryClient.setQueryData(detailQueryKey, updated);
			void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
			toast.success("Practice updated");
			onDone();
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				setConflict(true);
				return;
			}
			toast.error("Couldn't update the practice", { description: problemDetailOf(error) });
		},
	});
	const deleteOverride = useMutation({
		...adminDeleteCuratedPracticeOverrideMutation(),
		onSuccess: (updated: CuratedPractice) => {
			queryClient.setQueryData(detailQueryKey, updated);
			void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
			setBasePractice(updated);
			setFormGeneration((generation) => generation + 1);
			setConflict(false);
			toast.success(
				basePractice.status.state === "UPDATE_WAITING"
					? "Hephaestus update applied"
					: "Hephaestus default restored",
			);
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				toast.error(
					"The catalog changed before this action was saved. Reopen the practice to see the latest version.",
				);
				void queryClient.invalidateQueries({ queryKey: detailQueryKey });
				void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
				return;
			}
			toast.error("Couldn't apply the default", { description: problemDetailOf(error) });
		},
	});
	const keepCurrentDefinition = useMutation({
		...adminKeepCuratedPracticeMutation(),
		onSuccess: (updated: CuratedPractice) => {
			queryClient.setQueryData(detailQueryKey, updated);
			void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
			setBasePractice(updated);
			setConflict(false);
			toast.success(
				basePractice.status.state === "NO_LONGER_SHIPPED"
					? "Saved practice is now custom"
					: "Saved version kept",
			);
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				toast.error(
					"The catalog changed before this action was saved. Reopen the practice to see the latest version.",
				);
				void queryClient.invalidateQueries({ queryKey: detailQueryKey });
				void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
				return;
			}
			toast.error("Couldn't keep the saved version", { description: problemDetailOf(error) });
		},
	});

	const continueWithDraft = async () => {
		try {
			await queryClient.invalidateQueries({
				queryKey: detailQueryKey,
				exact: true,
				refetchType: "none",
			});
			const latest: CuratedPractice = await queryClient.query(detailOptions);
			setBasePractice(latest);
			setConflict(false);
		} catch (error) {
			toast.error("Couldn't refresh the latest version", { description: problemDetailOf(error) });
		}
	};

	return (
		<CuratedPracticeForm
			key={`${practiceSlug}-${formGeneration}`}
			mode="edit"
			cancel={<LevelCancel />}
			initialData={{
				slug: basePractice.slug,
				...basePractice.definition,
				bindings: [soleBinding(basePractice.definition.bindings)],
				precomputeScript: basePractice.definition.precomputeScript ?? undefined,
				whyItMatters: basePractice.definition.whyItMatters ?? undefined,
				whatGoodLooksLike: basePractice.definition.whatGoodLooksLike ?? undefined,
				groupSlug: basePractice.definition.groupSlug ?? undefined,
				status: basePractice.status,
				shipped: basePractice.shipped,
			}}
			groups={groups.map((group) => ({ slug: group.slug, name: group.definition.name }))}
			definitionOptions={definitionOptions}
			isPending={updatePractice.isPending}
			isResetPending={deleteOverride.isPending}
			isKeepPending={keepCurrentDefinition.isPending}
			conflict={conflict}
			onContinueWithDraft={() => void continueWithDraft()}
			onUseHephaestusVersion={() => {
				setConflict(false);
				deleteOverride.mutate({
					path: { slug: practiceSlug },
					headers: { "If-Match": `"${basePractice.status.etag}"` },
				});
			}}
			onKeepCurrentDefinition={() => {
				setConflict(false);
				keepCurrentDefinition.mutate({
					path: { slug: practiceSlug },
					headers: { "If-Match": `"${basePractice.status.etag}"` },
				});
			}}
			onSubmit={({ slug: _slug, ...definition }: CuratedPracticeFormValue) => {
				setConflict(false);
				updatePractice.mutate({
					path: { slug: practiceSlug },
					headers: { "If-Match": `"${basePractice.status.etag}"` },
					body: {
						...definition,
					},
				});
			}}
		/>
	);
}
