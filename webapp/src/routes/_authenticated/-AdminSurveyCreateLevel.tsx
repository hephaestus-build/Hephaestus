import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	adminCreateProductSurveyMutation,
	adminGetProductSurveyQueryKey,
	adminListProductSurveysQueryKey,
	adminListWorkspacesOptions,
} from "@/api/@tanstack/react-query.gen";
import { AdminSurveyComposer } from "@/components/admin/feedback/AdminSurveyComposer";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailDrawerHeader } from "@/components/core/detail-drawer/DetailDrawerHeader";
import { LevelCancel } from "@/components/core/detail-drawer/LevelCancel";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";
import { productSurveyQueryScope } from "@/hooks/use-product-feedback";
import { problemDetailOf } from "@/lib/problem-detail";

export interface AdminSurveyCreateLevelProps {
	nested?: boolean;
	/** Called with the published survey's id, so the host can open its results in place. */
	onPublished: (surveyId: string) => void;
}

/** The composer as a guarded drawer level — `webapp/AGENTS.md` § Guarded levels. */
export function AdminSurveyCreateLevel({ nested, onPublished }: AdminSurveyCreateLevelProps) {
	const queryClient = useQueryClient();
	const workspacesQuery = useQuery(adminListWorkspacesOptions());
	const create = useMutation({
		...adminCreateProductSurveyMutation(),
		onSuccess: (survey) => {
			queryClient.setQueryData(
				adminGetProductSurveyQueryKey({ path: { surveyId: survey.id } }),
				survey,
			);
			void queryClient.invalidateQueries({ queryKey: adminListProductSurveysQueryKey() });
			void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
			toast.success("Survey published.");
			onPublished(survey.id);
		},
		onError: (error) =>
			toast.error("Couldn't publish the survey. Your draft is still here.", {
				description: problemDetailOf(error),
			}),
	});

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="min-w-0 flex-1 space-y-0.5">
					<DrawerTitle>Create survey</DrawerTitle>
					<DrawerDescription>
						Members of the audience are invited from the app header while the survey is open.
					</DrawerDescription>
				</div>
			</DetailDrawerHeader>
			{/* Data first: a refetch that fails keeps the audiences it had, and the draft with them. */}
			{workspacesQuery.data ? (
				<AdminSurveyComposer
					workspaces={workspacesQuery.data}
					isPending={create.isPending}
					cancel={<LevelCancel />}
					onSubmit={(body) => create.mutateAsync({ body })}
				/>
			) : workspacesQuery.isError ? (
				<DrawerBody>
					<QueryErrorAlert
						error={workspacesQuery.error}
						title="Workspace audiences couldn't be loaded"
						onRetry={() => void workspacesQuery.refetch()}
					/>
				</DrawerBody>
			) : (
				<DrawerBody className="flex flex-col gap-5">
					<Skeleton className="h-8 w-full" />
					<Skeleton className="h-20 w-full" />
					<Skeleton className="h-8 w-56" />
					<Skeleton className="h-40 w-full" />
				</DrawerBody>
			)}
		</>
	);
}
