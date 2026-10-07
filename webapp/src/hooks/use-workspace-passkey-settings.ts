import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	getWorkspacePasskeyPolicyOptions,
	getWorkspacePasskeyPolicyQueryKey,
	updateWorkspacePasskeyPolicyMutation,
} from "@/api/@tanstack/react-query.gen";
import { problemDetailOf } from "@/lib/problem-detail";
import { workspaceMembershipQueryOptions } from "@/runtime/auth/guard";

import type { WorkspacePasskeyPolicySectionProps } from "@/components/admin/settings/WorkspacePasskeyPolicySection";

export function useWorkspacePasskeySettings({ workspaceSlug }: { workspaceSlug: string }) {
	const queryClient = useQueryClient();
	const options = { path: { workspaceSlug } };
	const policy = useQuery(getWorkspacePasskeyPolicyOptions(options));
	const membership = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const mutation = useMutation({
		...updateWorkspacePasskeyPolicyMutation(),
		onSuccess: async () => {
			await queryClient.invalidateQueries({ queryKey: getWorkspacePasskeyPolicyQueryKey(options) });
		},
	});
	let error: string | undefined;
	if (mutation.isError) {
		error = problemDetailOf(mutation.error);
	} else if (membership.isError) {
		error = problemDetailOf(
			membership.error,
			"We could not verify your workspace access. Try again.",
		);
	} else if (policy.isError) {
		error = problemDetailOf(policy.error);
	}
	return {
		required: membership.isError ? undefined : policy.data?.required,
		instanceRequired: policy.data?.instanceRequired,
		owner: membership.data?.role === "OWNER",
		loading: policy.isPending || membership.isPending,
		pending: mutation.isPending,
		error,
		onRetry: () => {
			void policy.refetch();
			void membership.refetch();
		},
		onChange: (required) => mutation.mutate({ ...options, body: { required } }),
	} satisfies WorkspacePasskeyPolicySectionProps;
}
