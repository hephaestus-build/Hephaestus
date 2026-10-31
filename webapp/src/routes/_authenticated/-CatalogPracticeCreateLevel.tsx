import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { toast } from "@/lib/toast";

import {
	createCatalogPracticeMutation,
	getInstanceCatalogOptions,
	getInstanceCatalogQueryKey,
	getInstanceDefinitionOptionsOptions,
} from "@/api/@tanstack/react-query.gen";
import { CatalogFormLevel } from "@/components/admin/catalog/CatalogFormLevel";
import { CatalogPracticeForm } from "@/components/admin/catalog/CatalogPracticeForm";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert, queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { DrawerBody } from "@/components/ui/drawer";
import { problemDetailOf } from "@/lib/problem-detail";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export interface CatalogPracticeCreateLevelProps {
	nested?: boolean;
	path: LevelPath;
	onDone: () => void;
}

export function CatalogPracticeCreateLevel({
	nested,
	path,
	onDone,
}: CatalogPracticeCreateLevelProps) {
	const queryClient = useQueryClient();
	const catalogOptions = getInstanceCatalogOptions();
	const definitionOptionsOptions = getInstanceDefinitionOptionsOptions();
	const catalogQuery = useQuery(catalogOptions);
	const definitionOptionsQuery = useQuery(definitionOptionsOptions);
	useQueryReadRefusal(catalogOptions.queryKey, catalogQuery.isError, catalogQuery.error);
	useQueryReadRefusal(
		definitionOptionsOptions.queryKey,
		definitionOptionsQuery.isError,
		definitionOptionsQuery.error,
	);
	const failed = [catalogQuery, definitionOptionsQuery].filter((read) => read.isError);
	// Denied reads hide cached drafts; transient refresh failures preserve them.
	const failure = failed.find((read) => !queryErrorIsRetryable(read.error)) ?? failed[0];
	const refused = failure !== undefined && !queryErrorIsRetryable(failure.error);
	const retryFailed = () => {
		for (const read of failed) {
			void read.refetch();
		}
	};
	const createPractice = useMutation({
		...createCatalogPracticeMutation(),
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
			toast.success("Practice created");
			onDone();
		},
		onError: (error) =>
			toast.error("We could not create the practice", { description: problemDetailOf(error) }),
	});

	let body: ReactNode;
	if (!refused && catalogQuery.data !== undefined && definitionOptionsQuery.data !== undefined) {
		body = (
			<CatalogPracticeForm
				mode="create"
				cancel={<LevelCancel />}
				groups={catalogQuery.data.groups.map(({ slug, name }) => ({ slug, name }))}
				isPending={createPractice.isPending}
				definitionOptions={definitionOptionsQuery.data}
				refreshAlert={
					failure === undefined ? undefined : (
						<QueryErrorAlert
							error={failure.error}
							title="We could not refresh the practice editor"
							pending={failed.some((read) => read.isFetching)}
							onRetry={retryFailed}
						/>
					)
				}
				onSubmit={({ slug, kind, groupSlug, definition }) =>
					createPractice.mutate({ body: { slug, kind, groupSlug, definition, position: null } })
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
					onRetry={retryFailed}
				/>
			</DrawerBody>
		);
	}

	return (
		<CatalogFormLevel kind="practice-new" nested={nested} path={path}>
			{body}
		</CatalogFormLevel>
	);
}
