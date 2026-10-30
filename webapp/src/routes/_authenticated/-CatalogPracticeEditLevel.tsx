import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import { toast } from "@/lib/toast";

import {
	acceptCatalogPracticeReleaseMutation,
	acknowledgeCatalogPracticeMutation,
	getCatalogPracticeOptions,
	getCatalogPracticeQueryKey,
	getCatalogPracticeReleaseOptions,
	getInstanceCatalogOptions,
	getInstanceCatalogQueryKey,
	getInstanceDefinitionOptionsOptions,
	resetCatalogPracticeMutation,
	updateCatalogPracticeMutation,
} from "@/api/@tanstack/react-query.gen";
import type { CatalogGroup, CatalogPractice, PracticeDefinitionOptions } from "@/api/types.gen";
import { CatalogFormLevel } from "@/components/admin/catalog/CatalogFormLevel";
import { CatalogPracticeForm } from "@/components/admin/catalog/CatalogPracticeForm";
import { PracticeReleaseReview } from "@/components/admin/practices/PracticeReleaseReview";
import {
	PracticeDefinitionSkeleton,
	PracticeReleaseSkeleton,
} from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert, queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { DrawerBody } from "@/components/ui/drawer";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export interface CatalogPracticeEditLevelProps {
	practiceSlug: string;
	nested?: boolean;
	path: LevelPath;
	onDone: () => void;
}

export function CatalogPracticeEditLevel({
	practiceSlug,
	nested,
	path,
	onDone,
}: CatalogPracticeEditLevelProps) {
	const practiceOptions = getCatalogPracticeOptions({ path: { slug: practiceSlug } });
	const catalogOptions = getInstanceCatalogOptions();
	const definitionOptionsOptions = getInstanceDefinitionOptionsOptions();
	const practiceQuery = useQuery(practiceOptions);
	const catalogQuery = useQuery(catalogOptions);
	const definitionOptionsQuery = useQuery(definitionOptionsOptions);
	useQueryReadRefusal(practiceOptions.queryKey, practiceQuery.isError, practiceQuery.error);
	useQueryReadRefusal(catalogOptions.queryKey, catalogQuery.isError, catalogQuery.error);
	useQueryReadRefusal(
		definitionOptionsOptions.queryKey,
		definitionOptionsQuery.isError,
		definitionOptionsQuery.error,
	);
	const failed = [practiceQuery, catalogQuery, definitionOptionsQuery].filter(
		(read) => read.isError,
	);
	// Denied reads hide cached drafts; transient refresh failures preserve them.
	const failure = failed.find((read) => !queryErrorIsRetryable(read.error)) ?? failed[0];
	const refused = failure !== undefined && !queryErrorIsRetryable(failure.error);
	const retryFailed = () => {
		for (const read of failed) {
			void read.refetch();
		}
	};

	let body: ReactNode;
	if (
		!refused &&
		practiceQuery.data !== undefined &&
		catalogQuery.data !== undefined &&
		definitionOptionsQuery.data !== undefined
	) {
		body = (
			<LoadedCatalogPracticeEditor
				key={practiceSlug}
				practiceSlug={practiceSlug}
				initialPractice={practiceQuery.data}
				groups={catalogQuery.data.groups}
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
				onDone={onDone}
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
					title="We could not load the practice"
					onRetry={retryFailed}
				/>
			</DrawerBody>
		);
	}

	return (
		<CatalogFormLevel kind="practice-edit" nested={nested} path={path}>
			{body}
		</CatalogFormLevel>
	);
}

interface LoadedCatalogPracticeEditorProps {
	practiceSlug: string;
	initialPractice: CatalogPractice;
	groups: CatalogGroup[];
	definitionOptions: PracticeDefinitionOptions;
	refreshAlert: ReactNode;
	onDone: () => void;
}

function LoadedCatalogPracticeEditor({
	practiceSlug,
	initialPractice,
	groups,
	definitionOptions,
	refreshAlert,
	onDone,
}: LoadedCatalogPracticeEditorProps) {
	const queryClient = useQueryClient();
	const [basePractice, setBasePractice] = useState(initialPractice);
	const [conflict, setConflict] = useState(false);
	const [formGeneration, setFormGeneration] = useState(0);
	const detailOptions = getCatalogPracticeOptions({ path: { slug: practiceSlug } });
	const detailQueryKey = getCatalogPracticeQueryKey({ path: { slug: practiceSlug } });
	const releaseQuery = useQuery({
		...getCatalogPracticeReleaseOptions({ path: { slug: practiceSlug } }),
		enabled: basePractice.state === "UPDATE_WAITING",
	});
	const acceptRelease = useMutation({
		...acceptCatalogPracticeReleaseMutation(),
		onSuccess: (updated) => {
			queryClient.setQueryData(detailQueryKey, updated);
			void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
			setBasePractice(updated);
			setFormGeneration((generation) => generation + 1);
			toast.success("Selected practice changes accepted");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				void continueWithDraft();
				void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
				void releaseQuery.refetch();
			}
			toast.error("We could not accept the update", { description: problemDetailOf(error) });
		},
	});
	const declineRelease = useMutation({
		...acknowledgeCatalogPracticeMutation(),
		onSuccess: (updated) => {
			queryClient.setQueryData(detailQueryKey, updated);
			void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
			setBasePractice(updated);
			toast.success("Practice update declined");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				void continueWithDraft();
				void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
				void releaseQuery.refetch();
			}
			toast.error("We could not decline the update", { description: problemDetailOf(error) });
		},
	});
	const updatePractice = useMutation({
		...updateCatalogPracticeMutation(),
		onSuccess: (updated) => {
			queryClient.setQueryData(detailQueryKey, updated);
			void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
			toast.success("Practice updated");
			onDone();
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				setConflict(true);
				return;
			}
			toast.error("We could not update the practice", { description: problemDetailOf(error) });
		},
	});
	const resetPractice = useMutation({
		...resetCatalogPracticeMutation(),
		onSuccess: (updated: CatalogPractice) => {
			queryClient.setQueryData(detailQueryKey, updated);
			void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
			setBasePractice(updated);
			setFormGeneration((generation) => generation + 1);
			setConflict(false);
			toast.success(
				basePractice.state === "UPDATE_WAITING"
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
				void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
				return;
			}
			toast.error("We could not apply the Hephaestus version", {
				description: problemDetailOf(error),
			});
		},
	});
	const keepCurrentDefinition = useMutation({
		...acknowledgeCatalogPracticeMutation(),
		onSuccess: (updated: CatalogPractice) => {
			queryClient.setQueryData(detailQueryKey, updated);
			void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
			setBasePractice(updated);
			setConflict(false);
			toast.success(
				basePractice.state === "NO_LONGER_SHIPPED"
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
				void queryClient.invalidateQueries({ queryKey: getInstanceCatalogQueryKey() });
				return;
			}
			toast.error("We could not keep the saved version", { description: problemDetailOf(error) });
		},
	});

	const continueWithDraft = async () => {
		try {
			await queryClient.invalidateQueries({
				queryKey: detailQueryKey,
				exact: true,
				refetchType: "none",
			});
			const latest: CatalogPractice = await queryClient.query({ ...detailOptions, retry: false });
			setBasePractice(latest);
			setConflict(false);
		} catch (error) {
			toast.error("We could not load the current version", { description: problemDetailOf(error) });
		}
	};
	let releaseReview: ReactNode;
	if (releaseQuery.isPending) {
		releaseReview = <PracticeReleaseSkeleton rows={3} />;
	} else if (releaseQuery.isError) {
		releaseReview = (
			<QueryErrorAlert
				error={releaseQuery.error}
				title="We could not load the update"
				onRetry={() => {
					void releaseQuery.refetch();
				}}
			/>
		);
	} else {
		const release = releaseQuery.data;
		releaseReview = (
			<PracticeReleaseReview
				key={release.etag}
				proposal={release}
				pending={
					(acceptRelease.isPending ? "accept" : null) ??
					(declineRelease.isPending ? "decline" : null)
				}
				declineNote="Declining keeps your practice as it is until the next Hephaestus update. Restore Hephaestus default stays available on the practice."
				onAccept={(choices) =>
					acceptRelease.mutate({
						path: { slug: practiceSlug },
						headers: { "If-Match": `"${release.etag}"` },
						body: { choices },
					})
				}
				onDecline={() =>
					declineRelease.mutate({
						path: { slug: practiceSlug },
						headers: { "If-Match": `"${release.etag}"` },
					})
				}
			/>
		);
	}

	return (
		<CatalogPracticeForm
			key={`${practiceSlug}-${formGeneration}`}
			mode="edit"
			cancel={<LevelCancel />}
			practice={basePractice}
			groups={groups.map(({ slug, name }) => ({ slug, name }))}
			definitionOptions={definitionOptions}
			isPending={updatePractice.isPending}
			disabled={acceptRelease.isPending}
			isResetPending={resetPractice.isPending}
			isKeepPending={keepCurrentDefinition.isPending}
			releaseReview={releaseReview}
			refreshAlert={refreshAlert}
			conflict={conflict}
			onContinueWithDraft={() => {
				void continueWithDraft();
			}}
			onUseHephaestusVersion={() => {
				setConflict(false);
				resetPractice.mutate({
					path: { slug: practiceSlug },
					headers: { "If-Match": `"${basePractice.etag}"` },
				});
			}}
			onKeepCurrentDefinition={() => {
				setConflict(false);
				keepCurrentDefinition.mutate({
					path: { slug: practiceSlug },
					headers: { "If-Match": `"${basePractice.etag}"` },
				});
			}}
			onSubmit={({ groupSlug, definition }) => {
				setConflict(false);
				updatePractice.mutate({
					path: { slug: practiceSlug },
					headers: { "If-Match": `"${basePractice.etag}"` },
					body: {
						kind: basePractice.kind,
						groupSlug,
						definition,
						position: null,
					},
				});
			}}
		/>
	);
}
