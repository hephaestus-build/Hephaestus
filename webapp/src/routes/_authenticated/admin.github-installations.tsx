import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";

import {
	adoptGitHubInstallationMutation,
	declineGitHubInstallationMutation,
	listGitHubInstallationsOptions,
} from "@/api/@tanstack/react-query.gen";
import type { GitHubInstallationRequest } from "@/api/types.gen";
import { AdminGitHubInstallationsTable } from "@/components/admin/github-installations/AdminGitHubInstallationsTable";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { GitHubIcon } from "@/components/icons/brand";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { filedUnder, pathNumber, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";
import { toast } from "@/lib/toast";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute("/_authenticated/admin/github-installations")({
	head: instanceAdminHead("GitHub installations"),
	component: AdminGitHubInstallationsPage,
});

const DECISION_MUTATION_KEY = ["adminDecideGitHubInstallation"];

const path = (installation: GitHubInstallationRequest) => ({
	path: { installationId: installation.installationId },
});

function AdminGitHubInstallationsPage() {
	const queryClient = useQueryClient();
	const listOptions = listGitHubInstallationsOptions();
	const listQuery = useQuery(listOptions);
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	// A retryable failed refresh keeps the installations this key last read, with the failure beside
	// them. A refusal, or a read that never succeeded, shows none.
	const retained = listQuery.isRefetchError && queryErrorIsRetryable(listQuery.error);
	const failed = listQuery.isError && !retained;
	const failure = {
		error: listQuery.error,
		pending: listQuery.isFetching,
		onRetry: () => {
			void listQuery.refetch();
		},
	};

	const [challenge, setChallenge] = useState<StepUpChallenge | undefined>(undefined);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	/** A refusal that asks for a fresh sign-in opens that ask instead of a toast. */
	const reportError = (error: unknown, fallback: string) => {
		const stepUp = stepUpChallengeOf(error);
		if (stepUp !== undefined) {
			setChallenge(stepUp);
			return;
		}
		toast.error(problemDetailOf(error, fallback));
	};
	const settle = async () => queryClient.invalidateQueries({ queryKey: listOptions.queryKey });

	const adoptMutation = useMutation({
		...filedUnder(DECISION_MUTATION_KEY, adoptGitHubInstallationMutation()),
		onSuccess: (installation) => {
			toast.success(`Adopted the installation on ${installation.accountLogin}`);
		},
		onError: (error) => reportError(error, "We could not adopt the installation. Try again."),
		onSettled: settle,
	});
	const declineMutation = useMutation({
		...filedUnder(DECISION_MUTATION_KEY, declineGitHubInstallationMutation()),
		onSuccess: (installation) => {
			toast.success(`Declined the installation on ${installation.accountLogin}`);
		},
		onError: (error) => reportError(error, "We could not decline the installation. Try again."),
		onSettled: settle,
	});
	const deciding = usePendingMutationIds(DECISION_MUTATION_KEY, (variables) =>
		pathNumber(variables, "installationId"),
	);

	return (
		<PageLayout>
			<PageHeader
				icon={<GitHubIcon />}
				title="GitHub installations"
				description="A new installation of the GitHub App waits here until an instance admin adopts it. Adopting it creates its workspace, with you as its owner."
			/>

			<AdminGitHubInstallationsTable
				installations={failed ? [] : (listQuery.data ?? [])}
				isLoading={listQuery.isLoading}
				loadFailure={failed ? failure : undefined}
				refreshFailure={retained ? failure : undefined}
				deciding={deciding}
				onAdopt={(installation) => adoptMutation.mutate(path(installation))}
				onDecline={(installation) => declineMutation.mutate(path(installation))}
			/>

			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) {
						setChallenge(undefined);
					}
				}}
				maxAgeSeconds={challenge?.maxAgeSeconds}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</PageLayout>
	);
}
