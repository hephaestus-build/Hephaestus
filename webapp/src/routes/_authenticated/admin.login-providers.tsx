import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { KeyRound, Plus } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import {
	adminApproveDirectoryGroupsMutation,
	adminCreateLoginProviderMutation,
	adminDeleteLoginProviderMutation,
	adminListLoginProvidersOptions,
	adminListLoginProvidersQueryKey,
	adminUpdateLoginProviderMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	CreateLoginProviderRequest,
	LoginProviderView,
	UpdateLoginProviderRequest,
} from "@/api/types.gen";
import { InstanceDirectoryGroupsDialog } from "@/components/admin/login-providers/InstanceDirectoryGroupsDialog";
import { LoginProviderFormDialog } from "@/components/admin/login-providers/LoginProviderFormDialog";
import { LoginProvidersTable } from "@/components/admin/login-providers/LoginProvidersTable";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Button } from "@/components/ui/button";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/admin/login-providers")({
	head: instanceAdminHead("Login providers"),
	component: AdminLoginProvidersPage,
});

const PROVIDER_WRITE_MUTATION_KEY = ["adminWriteLoginProvider"];

function AdminLoginProvidersPage() {
	const queryClient = useQueryClient();
	const listQuery = useQuery(adminListLoginProvidersOptions());
	const providers: LoginProviderView[] = listQuery.data ?? [];

	const [directoryProvider, setDirectoryProvider] = useState<LoginProviderView | null>(null);
	const [dialogOpen, setDialogOpen] = useState(false);
	const [editing, setEditing] = useState<LoginProviderView | null>(null);

	const invalidate = () =>
		queryClient.invalidateQueries({ queryKey: adminListLoginProvidersQueryKey() });

	const [challenge, setChallenge] = useState<StepUpChallenge | undefined>(undefined);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	/**
	 * A refusal that asks for a fresh sign-in is not a failure the operator can read and act on, so
	 * it opens the ask instead of a toast — and it replaces the form dialog rather than stacking a
	 * second focus trap on top of it.
	 */
	const reportError = (error: unknown, fallback: string) => {
		const stepUp = stepUpChallengeOf(error);
		if (stepUp) {
			setDialogOpen(false);
			setDirectoryProvider(null);
			setChallenge(stepUp);
			return;
		}
		toast.error(problemDetailOf(error, fallback));
	};

	const createMutation = useMutation({
		...adminCreateLoginProviderMutation(),
		onSuccess: () => {
			void invalidate();
			setDialogOpen(false);
			toast.success("Login provider added");
		},
		onError: (error) => reportError(error, "Could not add the login provider"),
	});

	const updateMutation = useMutation({
		...filedUnder(PROVIDER_WRITE_MUTATION_KEY, adminUpdateLoginProviderMutation()),
		onSuccess: () => {
			void invalidate();
			setDialogOpen(false);
			toast.success("Login provider updated");
		},
		onError: (error) => reportError(error, "Could not update the login provider"),
	});

	const deleteMutation = useMutation({
		...filedUnder(PROVIDER_WRITE_MUTATION_KEY, adminDeleteLoginProviderMutation()),
		onSuccess: () => {
			void invalidate();
			toast.success("Login provider deleted");
		},
		onError: (error) => reportError(error, "Could not delete the login provider"),
	});

	const directoryMutation = useMutation({
		...filedUnder(PROVIDER_WRITE_MUTATION_KEY, adminApproveDirectoryGroupsMutation()),
		onSuccess: () => {
			void invalidate();
			setDirectoryProvider(null);
			toast.success("Directory group approval updated");
		},
		onError: (error) => reportError(error, "Could not update directory approval"),
	});
	const mutatingIds = usePendingMutationIds(PROVIDER_WRITE_MUTATION_KEY, (variables) =>
		pathString(variables, "registrationId"),
	);

	const openCreate = () => {
		setEditing(null);
		setDialogOpen(true);
	};
	const openEdit = (provider: LoginProviderView) => {
		setEditing(provider);
		setDialogOpen(true);
	};

	const handleCreate = (body: CreateLoginProviderRequest) => createMutation.mutate({ body });
	const handleUpdate = (registrationId: string, body: UpdateLoginProviderRequest) => {
		updateMutation.mutate({ path: { registrationId }, body });
	};
	const handleToggleEnabled = (provider: LoginProviderView, enabled: boolean) => {
		updateMutation.mutate({ path: { registrationId: provider.registrationId }, body: { enabled } });
	};
	const handleDelete = (provider: LoginProviderView) => {
		deleteMutation.mutate({ path: { registrationId: provider.registrationId } });
	};

	return (
		<PageLayout>
			<PageHeader
				icon={<KeyRound />}
				title="Login providers"
				description="Configure OAuth providers for sign-in and account linking."
				actions={
					<Button onClick={openCreate}>
						<Plus className="size-4" aria-hidden />
						Add provider
					</Button>
				}
			/>

			<LoginProvidersTable
				providers={providers}
				isLoading={listQuery.isLoading}
				isError={listQuery.isError}
				error={listQuery.error}
				onRetry={() => void listQuery.refetch()}
				mutatingIds={mutatingIds}
				onEdit={openEdit}
				onToggleEnabled={handleToggleEnabled}
				onDelete={handleDelete}
				onAdd={openCreate}
				onDirectoryGroups={setDirectoryProvider}
			/>

			<LoginProviderFormDialog
				open={dialogOpen}
				onOpenChange={setDialogOpen}
				editing={editing}
				isSubmitting={createMutation.isPending || updateMutation.isPending}
				onCreate={handleCreate}
				onUpdate={handleUpdate}
			/>

			<InstanceDirectoryGroupsDialog
				provider={directoryProvider}
				isSaving={directoryMutation.isPending}
				onClose={() => setDirectoryProvider(null)}
				onSave={(groupIds) => {
					if (directoryProvider)
						directoryMutation.mutate({
							path: { registrationId: directoryProvider.registrationId },
							body: { groupIds },
						});
				}}
			/>
			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) setChallenge(undefined);
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
