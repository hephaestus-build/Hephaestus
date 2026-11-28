import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	listOwnSupportSharesOptions,
	listOwnSupportSharesQueryKey,
	revokeSupportShareMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	SupportSharesSectionProps,
	SupportSharesState,
} from "@/components/settings/SupportSharesSection";
import { problemDetailOf } from "@/lib/problem-detail";
import { toast } from "@/lib/toast";

/** The reader's support shares for their settings, and the revoke that ends one at once. */
export function useOwnSupportShares(): SupportSharesSectionProps {
	const queryClient = useQueryClient();
	const query = useQuery(listOwnSupportSharesOptions());
	const revoke = useMutation({
		...revokeSupportShareMutation(),
		onSuccess: () => {
			toast.success("Share revoked. The admin can no longer read it.");
		},
		onError: (error) => {
			toast.error("We could not revoke this share", { description: problemDetailOf(error) });
		},
		onSettled: async () => {
			await queryClient.invalidateQueries({ queryKey: listOwnSupportSharesQueryKey() });
		},
	});
	let state: SupportSharesState;
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
		state = { status: "ready", shares: query.data };
	}
	return {
		state,
		revoking: revoke.isPending ? revoke.variables.path.shareId : undefined,
		onRevoke: (shareId) => revoke.mutate({ path: { shareId } }),
	};
}
