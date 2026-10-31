import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { KeyRound, Plus } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { toast } from "@/lib/toast";

import {
	adminCreateLoginProviderMutation,
	adminDeleteLoginProviderMutation,
	adminListLoginProvidersOptions,
	adminUpdateLoginProviderMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	CreateLoginProviderRequest,
	LoginProviderView,
	UpdateLoginProviderRequest,
} from "@/api/types.gen";
import { LoginProviderFormDialog } from "@/components/admin/login-providers/LoginProviderFormDialog";
import { LoginProvidersTable } from "@/components/admin/login-providers/LoginProvidersTable";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Button } from "@/components/ui/button";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute("/_authenticated/admin/login-providers")({
	head: instanceAdminHead("Login providers"),
	component: AdminLoginProvidersPage,
});

const PROVIDER_WRITE_MUTATION_KEY = ["adminWriteLoginProvider"];

const UPDATE_FAILED = "We could not update the login provider. Try again.";

/** A new object each time the form opens, so a save settles against the form that sent it. */
interface ProviderForm {
	open: boolean;
	editing: LoginProviderView | null;
}

function AdminLoginProvidersPage() {
	const queryClient = useQueryClient();
	const listOptions = adminListLoginProvidersOptions();
	const listQuery = useQuery(listOptions);
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	// A refused read takes back the providers it listed, so an open edit of one goes with them. A
	// provider the admin is still adding is their own draft, and it stays.
	const refused = listQuery.isError && !queryErrorIsRetryable(listQuery.error);
	const providers: LoginProviderView[] = refused ? [] : (listQuery.data ?? []);

	const [form, setForm] = useState<ProviderForm>({ open: false, editing: null });
	if (refused && form.open && form.editing !== null) {
		setForm({ ...form, open: false });
	}
	// The form on screen, read when a save is sent and when it settles. A mutation callback's own
	// closure can be older than both (ADR 0027). A layout effect updates it in the commit itself; a
	// passive one can run after a save has already settled against the previous form.
	const formOnScreen = useRef<ProviderForm | null>(null);
	useLayoutEffect(() => {
		formOnScreen.current = form.open ? form : null;
	}, [form]);
	const closeForm = (sentFrom: ProviderForm | null | undefined) => {
		setForm((current) => (current === sentFrom ? { ...current, open: false } : current));
	};

	const invalidate = async () => queryClient.invalidateQueries({ queryKey: listOptions.queryKey });

	const [challenge, setChallenge] = useState<StepUpChallenge | undefined>(undefined);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	/**
	 * A refusal that asks for a fresh sign-in is not a failure the operator can read and act on, so
	 * it opens the ask instead of a toast. The ask replaces the form that sent the change rather than
	 * stacking a second focus trap on top of it. It never replaces a form opened since: that refusal
	 * is a toast.
	 */
	const reportError = (error: unknown, fallback: string, sentFrom?: ProviderForm | null) => {
		const stepUp = stepUpChallengeOf(error);
		const onScreen = formOnScreen.current;
		if (stepUp !== undefined && (onScreen === null || onScreen === sentFrom)) {
			closeForm(sentFrom);
			setChallenge(stepUp);
			return;
		}
		toast.error(problemDetailOf(error, fallback));
	};

	// TanStack hands the `onMutate` result to the save's own callbacks, and keeps it as its `context`.
	const sentFromForm = () => formOnScreen.current;

	const createMutation = useMutation({
		...adminCreateLoginProviderMutation(),
		onMutate: sentFromForm,
		onSuccess: (_data, _variables, sentFrom) => {
			void invalidate();
			closeForm(sentFrom);
			toast.success("Login provider added");
		},
		onError: (error, _variables, sentFrom) =>
			reportError(error, "We could not add the login provider. Try again.", sentFrom),
	});

	const updateMutation = useMutation({
		...filedUnder(PROVIDER_WRITE_MUTATION_KEY, adminUpdateLoginProviderMutation()),
		onMutate: sentFromForm,
		onSuccess: (_data, _variables, sentFrom) => {
			void invalidate();
			closeForm(sentFrom);
			toast.success("Login provider updated");
		},
		onError: (error, _variables, sentFrom) => reportError(error, UPDATE_FAILED, sentFrom),
	});

	// A row toggle belongs to no form, so its outcome never closes or holds one.
	const toggleMutation = useMutation({
		...filedUnder(PROVIDER_WRITE_MUTATION_KEY, adminUpdateLoginProviderMutation()),
		onSuccess: () => {
			void invalidate();
			toast.success("Login provider updated");
		},
		onError: (error) => reportError(error, UPDATE_FAILED),
	});

	const deleteMutation = useMutation({
		...filedUnder(PROVIDER_WRITE_MUTATION_KEY, adminDeleteLoginProviderMutation()),
		onSuccess: () => {
			void invalidate();
			toast.success("Login provider deleted");
		},
		onError: (error) => reportError(error, "We could not delete the login provider. Try again."),
	});

	const mutatingIds = usePendingMutationIds(PROVIDER_WRITE_MUTATION_KEY, (variables) =>
		pathString(variables, "registrationId"),
	);

	const openCreate = () => setForm({ open: true, editing: null });
	const openEdit = (provider: LoginProviderView) => setForm({ open: true, editing: provider });

	const handleCreate = (body: CreateLoginProviderRequest) => createMutation.mutate({ body });
	const handleUpdate = (registrationId: string, body: UpdateLoginProviderRequest) => {
		updateMutation.mutate({ path: { registrationId }, body });
	};
	const handleToggleEnabled = (provider: LoginProviderView, enabled: boolean) => {
		toggleMutation.mutate({ path: { registrationId: provider.registrationId }, body: { enabled } });
	};
	const handleDelete = (provider: LoginProviderView) => {
		deleteMutation.mutate({ path: { registrationId: provider.registrationId } });
	};

	return (
		<PageLayout>
			<PageHeader
				icon={<KeyRound />}
				title="Login providers"
				description="Configure OAuth providers for sign-in and for connecting accounts."
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
				onRetry={() => {
					void listQuery.refetch();
				}}
				mutatingIds={mutatingIds}
				onEdit={openEdit}
				onToggleEnabled={handleToggleEnabled}
				onDelete={handleDelete}
				onAdd={openCreate}
			/>

			<LoginProviderFormDialog
				open={form.open}
				onOpenChange={(open) => setForm({ ...form, open })}
				editing={form.editing}
				isSubmitting={
					(createMutation.isPending && createMutation.context === form) ||
					(updateMutation.isPending && updateMutation.context === form)
				}
				onCreate={handleCreate}
				onUpdate={handleUpdate}
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
