import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Settings2 } from "lucide-react";
import { toast } from "sonner";

import {
	adminGetInstanceSettingsOptions,
	adminGetInstanceSettingsQueryKey,
	adminSendTestEmailMutation,
	adminUpdateSilentModeMutation,
} from "@/api/@tanstack/react-query.gen";
import { EMAIL_TEST_OUTCOME_DEFS } from "@/components/admin/instance/email-test-outcome-defs";
import { InstanceEmailCard } from "@/components/admin/instance/InstanceEmailCard";
import { SilentModeCard } from "@/components/admin/instance/SilentModeCard";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/admin/settings")({
	head: instanceAdminHead("Instance settings"),
	component: AdminSettingsPage,
});

function AdminSettingsPage() {
	const queryClient = useQueryClient();
	const settingsQuery = useQuery(adminGetInstanceSettingsOptions());

	const silentModeMutation = useMutation({
		...adminUpdateSilentModeMutation(),
		onSuccess: (data) => {
			queryClient.setQueryData(adminGetInstanceSettingsQueryKey(), data);
			toast.success(
				data.silentModeEngaged
					? "Silent mode engaged — workspace feedback and messages are now suppressed"
					: "Silent mode released — workspace feedback and messages can go out again",
			);
		},
		onError: async (error) => {
			if (problemStatusOf(error) === 412) {
				await queryClient.invalidateQueries({ queryKey: adminGetInstanceSettingsQueryKey() });
				toast.error("Instance settings changed. Verify the current state before trying again.");
				return;
			}
			void queryClient.invalidateQueries({ queryKey: adminGetInstanceSettingsQueryKey() });
			toast.error(problemDetailOf(error, "Could not update silent mode"));
		},
	});

	const testEmailMutation = useMutation({
		...adminSendTestEmailMutation(),
		onSuccess: (data) => {
			const def = EMAIL_TEST_OUTCOME_DEFS[data.outcome];
			if (data.outcome === "SENT") {
				toast.success(`Relay accepted test email to ${data.to ?? "your address"}`);
			} else {
				toast.warning(def.label, { description: def.description });
			}
		},
		onError: (error) => {
			toast.error(problemDetailOf(error, "Could not send the test email"));
		},
	});

	return (
		<PageLayout>
			<PageHeader
				icon={<Settings2 />}
				title="Instance settings"
				description="Instance-wide operator controls. These apply across every workspace and override workspace settings while active."
			/>

			<div className="space-y-4">
				{settingsQuery.data ? (
					<>
						{settingsQuery.isError ? (
							<QueryErrorAlert
								error={settingsQuery.error}
								title="Couldn't verify the current instance settings"
								onRetry={() => void settingsQuery.refetch()}
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
					</>
				) : settingsQuery.isError ? (
					<QueryErrorAlert
						error={settingsQuery.error}
						title="Couldn't load instance settings"
						onRetry={() => void settingsQuery.refetch()}
					/>
				) : (
					<Skeleton className="h-52 w-full rounded-xl" />
				)}
				<InstanceEmailCard
					isPending={testEmailMutation.isPending}
					result={testEmailMutation.data}
					onSendTest={(to) => testEmailMutation.mutate({ body: { to } })}
				/>
			</div>
		</PageLayout>
	);
}
