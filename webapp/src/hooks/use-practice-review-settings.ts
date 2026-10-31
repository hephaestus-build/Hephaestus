import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
	getFeedbackModeRollupQueryKey,
	getPracticeReviewSettingsQueryKey,
	listPracticeGroupsQueryKey,
	listPracticesQueryKey,
	updatePracticeReviewSettingsMutation,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeReviewSettings } from "@/api/types.gen";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { toast } from "@/lib/toast";

export function usePracticeReviewSettingsMutation(
	workspaceSlug: string,
	messages: { success: string; error: string },
) {
	const queryClient = useQueryClient();
	const settingsQueryKey = getPracticeReviewSettingsQueryKey({ path: { workspaceSlug } });
	const mutationKey = ["workspace", workspaceSlug, "practice-review-settings"] as const;

	return useMutation({
		...updatePracticeReviewSettingsMutation(),
		mutationKey,
		scope: { id: `workspace:${workspaceSlug}:practice-review-settings` },
		onMutate: async (variables) => {
			await queryClient.cancelQueries({ queryKey: settingsQueryKey });
			const previous = queryClient.getQueryData<PracticeReviewSettings>(settingsQueryKey);
			if (previous && !hasText(variables.headers?.["If-Match"])) {
				variables.headers = { "If-Match": previous.etag };
			}
			if (previous && variables.body.reset?.some((field) => field !== "REVIEW_SCOPE") !== true) {
				const patch = variables.body;
				const { reset } = patch;
				queryClient.setQueryData<PracticeReviewSettings>(settingsQueryKey, {
					...previous,
					...(reset?.includes("REVIEW_SCOPE") === true
						? {
								reviewScope: {
									people: "ALL" as const,
									repositories: "ALL" as const,
									accountIds: [],
									selectedRepositories: [],
								},
							}
						: {}),
					...(patch.reviewScope ? { reviewScope: patch.reviewScope } : {}),
					...(patch.deliveryStatus ? { deliveryStatus: patch.deliveryStatus } : {}),
					...(patch.defaultFeedbackMode
						? {
								defaultFeedbackMode: patch.defaultFeedbackMode,
								defaultFeedbackModeOverride: patch.defaultFeedbackMode,
							}
						: {}),
					...(patch.deliverToMerged === undefined
						? {}
						: {
								deliverToMerged: patch.deliverToMerged,
								deliverToMergedOverride: patch.deliverToMerged,
							}),
					...(patch.cooldownMinutes === undefined
						? {}
						: {
								cooldownMinutes: patch.cooldownMinutes,
								cooldownMinutesOverride: patch.cooldownMinutes,
							}),
				});
			}
			return { previous };
		},
		onSuccess: (updated) => {
			queryClient.setQueryData(settingsQueryKey, updated);
			toast.success(messages.success);
		},
		onError: (error, _variables, context) => {
			if (context?.previous) {
				queryClient.setQueryData(settingsQueryKey, context.previous);
			}
			if (problemStatusOf(error) === 412) {
				toast.error("Review settings changed elsewhere", {
					description: "The latest settings were reloaded. Review your change and try again.",
				});
				void queryClient.invalidateQueries({ queryKey: settingsQueryKey });
				return;
			}
			toast.error(messages.error, { description: problemDetailOf(error) });
		},
		onSettled: () => {
			if (queryClient.isMutating({ mutationKey }) !== 1) {
				return;
			}
			void queryClient.invalidateQueries({ queryKey: settingsQueryKey });
			void queryClient.invalidateQueries({
				queryKey: getFeedbackModeRollupQueryKey({ path: { workspaceSlug } }),
			});
			void queryClient.invalidateQueries({
				queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
			});
			void queryClient.invalidateQueries({
				queryKey: listPracticeGroupsQueryKey({ path: { workspaceSlug } }),
			});
		},
	});
}
