import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Settings2 } from "lucide-react";
import { type ReactNode, useState } from "react";
import { toast } from "sonner";

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
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { instanceAdminHead } from "@/lib/page-title";
import {
	problemDetailOf,
	problemStatusOf,
	type StepUpChallenge,
	stepUpChallengeOf,
} from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/admin/settings")({
	head: instanceAdminHead("Instance settings"),
	component: WorkspaceSettingsPage,
});

function WorkspaceSettingsPage() {
	const queryClient = useQueryClient();
	const settingsQuery = useQuery(adminGetInstanceSettingsOptions());

	const silentModeMutation = useMutation({
		...adminUpdateSilentModeMutation(),
		onSuccess: (data) => {
			queryClient.setQueryData(adminGetInstanceSettingsQueryKey(), data);
			toast.success(
				data.silentModeEngaged
					? "Silent mode is on. Workspace feedback and messages are withheld."
					: "Silent mode is off. Workspace feedback and messages can go out again.",
			);
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

	const policyQuery = useQuery(getPublicActivityPolicyOptions());
	// Changing who may publish asks for a recent sign-in; the refusal opens that ask instead of a toast.
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmAccess = useConfirmAccess(challenge !== undefined);
	const policyMutation = useMutation({
		...updatePublicActivityPolicyMutation(),
		onSuccess: (data) => {
			queryClient.setQueryData(getPublicActivityPolicyQueryKey(), data);
			// Whether each workspace's page is live follows the instance.
			void queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() });
			toast.success(
				data.allowed
					? "Workspaces can publish their activity page"
					: "Public activity pages are off",
				data.allowed
					? undefined
					: { description: "A copy already loaded can stay visible for up to a minute." },
			);
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
	let publicActivity: InstancePublicActivityState = { status: "loading" };
	if (policyQuery.data) {
		publicActivity = {
			status: "ready",
			allowed: policyQuery.data.allowed,
			pending: policyMutation.isPending,
		};
	} else if (policyQuery.isError) {
		publicActivity = {
			status: "error",
			error: policyQuery.error,
			onRetry: () => {
				void policyQuery.refetch();
			},
		};
	}

	let body: ReactNode;
	if (settingsQuery.data) {
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
					key={settingsQuery.data.etag}
					settings={settingsQuery.data}
					isPending={silentModeMutation.isPending}
					releaseDisabled={settingsQuery.isError}
					onEngage={(reason) => silentModeMutation.mutate({ body: { engaged: true, reason } })}
					onRelease={() =>
						silentModeMutation.mutate({
							headers: { "If-Match": settingsQuery.data.etag },
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
