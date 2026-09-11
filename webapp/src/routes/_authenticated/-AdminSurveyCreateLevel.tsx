import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	adminCreateProductSurveyMutation,
	adminGetProductSurveyQueryKey,
	adminListProductSurveysQueryKey,
	adminListWorkspacesOptions,
} from "@/api/@tanstack/react-query.gen";
import {
	AdminSurveyComposer,
	AdminSurveyComposerHeader,
} from "@/components/admin/feedback/AdminSurveyComposer";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { LevelCancel } from "@/components/core/detail-drawer/LevelCancel";
import { DrawerBody } from "@/components/ui/drawer";
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

	// Data first: a refetch that fails keeps the audiences it had, and the draft with them.
	if (workspacesQuery.data) {
		return (
			<AdminSurveyComposer
				nested={nested}
				workspaces={workspacesQuery.data}
				isPending={create.isPending}
				cancel={<LevelCancel />}
				onSubmit={(body) => create.mutateAsync({ body })}
			/>
		);
	}
	return (
		<>
			<AdminSurveyComposerHeader nested={nested} />
			{workspacesQuery.isError ? (
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
