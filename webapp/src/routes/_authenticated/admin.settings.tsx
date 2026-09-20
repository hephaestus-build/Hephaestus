import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Settings2 } from "lucide-react";
import type { ReactNode } from "react";
import { toast } from "sonner";

import {
	adminGetInstanceSettingsOptions,
	adminGetInstanceSettingsQueryKey,
	adminUpdateSilentModeMutation,
} from "@/api/@tanstack/react-query.gen";
import { SilentModeCard } from "@/components/admin/instance/SilentModeCard";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

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

	let body: ReactNode;
	if (settingsQuery.data) {
		body = (
			<div className="space-y-4">
				{settingsQuery.isError ? (
					<QueryErrorAlert
						error={settingsQuery.error}
						title="Couldn't verify the current instance settings"
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
				title="Couldn't load instance settings"
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
				description="Instance-wide operator controls. These apply across every workspace and override workspace settings while active."
			/>

			{body}
		</PageLayout>
	);
}
