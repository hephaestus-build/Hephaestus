import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Settings2 } from "lucide-react";
import { type ReactNode, useState } from "react";
import { usePasskeySettings } from "@/hooks/use-passkey-settings";
import { toast } from "@/lib/toast";

import {
	adminGetInstanceSettingsOptions,
	adminGetInstanceSettingsQueryKey,
	adminUpdateSilentModeMutation,
	adminSendTestEmailMutation,
	getPublicActivityPolicyOptions,
	getPublicActivityPolicyQueryKey,
	listWorkspacesQueryKey,
	updatePublicActivityPolicyMutation,
} from "@/api/@tanstack/react-query.gen";
import { EMAIL_TEST_OUTCOME_DEFS } from "@/components/admin/instance/email-test-outcome-defs";
import { InstanceEmailCard } from "@/components/admin/instance/InstanceEmailCard";
import {
	InstancePublicActivityCard,
	type InstancePublicActivityState,
} from "@/components/admin/instance/InstancePublicActivityCard";
import { SilentModeCard } from "@/components/admin/instance/SilentModeCard";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { QueryErrorAlert, queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { PasskeySettings } from "@/components/settings/PasskeySettings";
import { Skeleton } from "@/components/ui/skeleton";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { instanceAdminHead } from "@/lib/page-title";
import {
	problemDetailOf,
	problemStatusOf,
	type StepUpChallenge,
	stepUpChallengeOf,
} from "@/lib/problem-detail";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute("/_authenticated/admin/settings")({
	head: instanceAdminHead("Instance settings"),
	component: WorkspaceSettingsPage,
});

function WorkspaceSettingsPage() {
	const passkeys = usePasskeySettings();
	const queryClient = useQueryClient();
	const settingsOptions = adminGetInstanceSettingsOptions();
	const settingsQuery = useQuery(settingsOptions);
	useQueryReadRefusal(settingsOptions.queryKey, settingsQuery.isError, settingsQuery.error);

	const silentModeMutation = useMutation({
		...adminUpdateSilentModeMutation(),
		onSuccess: async (data) => {
			toast.success(
				data.silentModeEngaged
					? "Silent mode is on. Workspace feedback and messages are withheld."
					: "Silent mode is off. Workspace feedback and messages can go out again.",
			);
			// Like the public activity setting below, the saved state reaches the page only through a
			// fresh read.
			await queryClient.invalidateQueries({ queryKey: adminGetInstanceSettingsQueryKey() });
		},
		onError: async (error) => {
			if (problemStatusOf(error) === 412) {
				await queryClient.invalidateQueries({ queryKey: adminGetInstanceSettingsQueryKey() });
				toast.error("The instance settings changed. Check the current state, then try again.");
				return;
			}
			void queryClient.invalidateQueries({ queryKey: adminGetInstanceSettingsQueryKey() });
			toast.error(problemDetailOf(error, "We could not update silent mode. Try again."));
		},
	});

	const testEmailMutation = useMutation({
		...adminSendTestEmailMutation(),
		onSuccess: (data) => {
			const def = EMAIL_TEST_OUTCOME_DEFS[data.outcome];
			if (data.outcome === "SENT") {
				toast.success(`The mail server accepted the test email to ${data.to ?? "your address"}.`);
			} else {
				toast.warning(def.label, { description: def.description });
			}
		},
		onError: (error) => {
			toast.error(problemDetailOf(error, "We could not send the test email. Try again."));
		},
	});

	const policyOptions = getPublicActivityPolicyOptions();
	const policyQuery = useQuery(policyOptions);
	useQueryReadRefusal(policyOptions.queryKey, policyQuery.isError, policyQuery.error);
	// Changing who may publish asks for a recent sign-in; the refusal opens that ask instead of a toast.
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmAccess = useConfirmAccess(challenge !== undefined);
	const policyMutation = useMutation({
		...updatePublicActivityPolicyMutation(),
		onSuccess: async (data) => {
			// Whether each workspace's page is live follows the instance.
			void queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() });
			toast.success(
				data.allowed
					? "Workspaces can publish their activity page"
					: "Public activity pages are off",
				data.allowed
					? undefined
					: {
							description:
								"Visitors see the change when they load the page again, at most about a minute later.",
						},
			);
			// The saved setting reaches the card only through a fresh read, so a read that is refused
			// by the time the change returns stays refused.
			await queryClient.invalidateQueries({ queryKey: getPublicActivityPolicyQueryKey() });
		},
		onError: (error) => {
			const stepUp = stepUpChallengeOf(error);
			if (stepUp) {
				setChallenge(stepUp);
				return;
			}
			void queryClient.invalidateQueries({ queryKey: getPublicActivityPolicyQueryKey() });
			toast.error(problemDetailOf(error, "We could not change public activity pages. Try again."));
		},
	});
	const retryPolicy = () => {
		void policyQuery.refetch();
	};
	// A refused read shows nothing it read before. A read that can be retried keeps its last answer.
	const policy =
		policyQuery.isError && !queryErrorIsRetryable(policyQuery.error) ? undefined : policyQuery.data;
	let publicActivity: InstancePublicActivityState = { status: "loading" };
	if (policy !== undefined) {
		publicActivity = {
			status: "ready",
			allowed: policy.allowed,
			pending: policyMutation.isPending,
			refreshFailure: policyQuery.isError
				? { error: policyQuery.error, pending: policyQuery.isFetching, onRetry: retryPolicy }
				: undefined,
		};
	} else if (policyQuery.isError) {
		publicActivity = { status: "error", error: policyQuery.error, onRetry: retryPolicy };
	}

	const settings =
		settingsQuery.isError && !queryErrorIsRetryable(settingsQuery.error)
			? undefined
			: settingsQuery.data;
	let body: ReactNode;
	if (settings !== undefined) {
		body = (
			<div className="space-y-4">
				{settingsQuery.isError ? (
					<QueryErrorAlert
						error={settingsQuery.error}
						title="We could not verify the current instance settings"
						onRetry={() => {
							void settingsQuery.refetch();
						}}
					/>
				) : null}
				<SilentModeCard
					key={settings.etag}
					settings={settings}
					isPending={silentModeMutation.isPending}
					releaseDisabled={settingsQuery.isError}
					onEngage={(reason) => silentModeMutation.mutate({ body: { engaged: true, reason } })}
					onRelease={() =>
						silentModeMutation.mutate({
							headers: { "If-Match": settings.etag },
							body: { engaged: false },
						})
					}
				/>
			</div>
		);
	} else if (settingsQuery.isError) {
		body = (
			<QueryErrorAlert
				error={settingsQuery.error}
				title="We could not load instance settings"
				onRetry={() => {
					void settingsQuery.refetch();
				}}
			/>
		);
	} else {
		body = <Skeleton className="h-52 w-full rounded-xl" />;
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<Settings2 />}
				title="Instance settings"
				description="Instance-wide controls. These apply across every workspace and override workspace settings while active."
			/>

			<div className="space-y-4">
				{body}
				<InstancePublicActivityCard
					state={publicActivity}
					onAllowedChange={(allowed) => policyMutation.mutate({ body: { allowed } })}
				/>
				<InstanceEmailCard
					isPending={testEmailMutation.isPending}
					result={testEmailMutation.data}
					onSendTest={(to) => testEmailMutation.mutate({ body: { to } })}
				/>
			</div>
			<PasskeySettings {...passkeys} />

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
