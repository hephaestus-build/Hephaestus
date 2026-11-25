import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	getStartedMessagesOptions,
	getStartedMessagesQueryKey,
	updateStartedMessagesMutation,
} from "@/api/@tanstack/react-query.gen";
import type { StartedMessages } from "@/api/types.gen";
import type { WorkspaceStartedMessagesSettingsProps } from "@/components/admin/settings/WorkspaceStartedMessagesSettings";
import { problemDetailOf } from "@/lib/problem-detail";
import { toast } from "@/lib/toast";

/** Whether Hephaestus may start check-ins and updates for people in the workspace. */
export function useWorkspaceStartedMessages(
	workspaceSlug: string,
): Pick<WorkspaceStartedMessagesSettingsProps, "state" | "onChange"> {
	const queryClient = useQueryClient();
	const options = { path: { workspaceSlug } };
	const query = useQuery(getStartedMessagesOptions(options));
	const mutation = useMutation({
		...updateStartedMessagesMutation(),
		onSuccess: (data: StartedMessages) => {
			queryClient.setQueryData(getStartedMessagesQueryKey(options), data);
			toast.success(
				data.enabled
					? "Hephaestus may now send check-ins and updates here"
					: "Hephaestus now sends no check-ins or updates here",
			);
		},
		onError: (error) => {
			toast.error("We could not change this setting", { description: problemDetailOf(error) });
		},
	});
	let state: WorkspaceStartedMessagesSettingsProps["state"];
	if (query.isPending) {
		state = { status: "loading" };
	} else if (query.isError) {
		state = {
			status: "error",
			error: query.error,
			onRetry: () => {
				void query.refetch();
			},
		};
	} else {
		state = { status: "ready", enabled: query.data.enabled, pending: mutation.isPending };
	}
	return { state, onChange: (enabled) => mutation.mutate({ ...options, body: { enabled } }) };
}
