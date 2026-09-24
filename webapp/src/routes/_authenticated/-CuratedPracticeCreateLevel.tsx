import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { toast } from "sonner";

import {
	adminCreateCuratedPracticeMutation,
	adminGetCuratedCatalogOptions,
	adminGetCuratedCatalogQueryKey,
	adminGetPracticeDefinitionOptionsOptions,
} from "@/api/@tanstack/react-query.gen";
import { CuratedFormLevel } from "@/components/admin/curated-catalog/CuratedFormLevel";
import {
	CuratedPracticeForm,
	type CuratedPracticeFormValue,
} from "@/components/admin/curated-catalog/CuratedPracticeForm";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { DrawerBody } from "@/components/ui/drawer";
import { problemDetailOf } from "@/lib/problem-detail";

export interface CuratedPracticeCreateLevelProps {
	nested?: boolean;
	onDone: () => void;
}

export function CuratedPracticeCreateLevel({ nested, onDone }: CuratedPracticeCreateLevelProps) {
	const queryClient = useQueryClient();
	const catalogQuery = useQuery({ ...adminGetCuratedCatalogOptions() });
	const definitionOptionsQuery = useQuery({ ...adminGetPracticeDefinitionOptionsOptions() });
	const createPractice = useMutation({
		...adminCreateCuratedPracticeMutation(),
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: adminGetCuratedCatalogQueryKey() });
			toast.success("Practice created");
			onDone();
		},
		onError: (error) =>
			toast.error("Couldn't create the practice", { description: problemDetailOf(error) }),
	});

	let body: ReactNode;
	if (catalogQuery.isPending || definitionOptionsQuery.isPending) {
		body = (
			<DrawerBody>
				<PracticeDefinitionSkeleton />
			</DrawerBody>
		);
	} else if (catalogQuery.isError || definitionOptionsQuery.isError) {
		body = (
			<DrawerBody>
				<QueryErrorAlert
					error={catalogQuery.error ?? definitionOptionsQuery.error}
					title="Couldn't load the practice editor"
					onRetry={() => {
						void catalogQuery.refetch();
						void definitionOptionsQuery.refetch();
					}}
				/>
			</DrawerBody>
		);
	} else {
		body = (
			<CuratedPracticeForm
				mode="create"
				cancel={<LevelCancel />}
				groups={catalogQuery.data.groups.map((group) => ({
					slug: group.slug,
					name: group.definition.name,
				}))}
				isPending={createPractice.isPending}
				definitionOptions={definitionOptionsQuery.data}
				onSubmit={({
					slug,
					bindingChanges: _bindingChanges,
					...definition
				}: CuratedPracticeFormValue) => createPractice.mutate({ body: { slug, definition } })}
			/>
		);
	}

	return (
		<CuratedFormLevel kind="practice-new" nested={nested}>
			{body}
		</CuratedFormLevel>
	);
}
