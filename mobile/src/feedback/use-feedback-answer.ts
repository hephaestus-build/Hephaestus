import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as Haptics from "expo-haptics";
import { Platform } from "react-native";
import {
	deleteFeedbackResponseMutation,
	getFeedbackResponseQueryKey,
	replaceFeedbackResponseMutation,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackResponse, ReplaceFeedbackResponseData } from "@/api/types.gen";
import { useWorkspace } from "@/workspace/workspace-context";
import { feedbackResponseOptions } from "./response-query";

/** An unanswered response is 204. TanStack Query requires a defined cache value, so keep null. */
function savedAnswer(body: FeedbackResponse | null): { saved: FeedbackResponse | undefined } {
	return { saved: body ?? undefined };
}

/** Called only for feedback the route loaded; the server checks ownership again. */
export function useFeedbackAnswer(feedbackId: string, onChanged?: () => void) {
	const { workspaceSlug } = useWorkspace();
	const queryClient = useQueryClient();
	const path = { workspaceSlug, feedbackId };
	const response = useQuery({ ...feedbackResponseOptions({ path }), select: savedAnswer });
	const responseKey = getFeedbackResponseQueryKey({ path });
	const save = useMutation({
		...replaceFeedbackResponseMutation(),
		onSuccess: (saved) => {
			queryClient.setQueryData(responseKey, saved);
			onChanged?.();
			// iOS confirms a saved choice with a tap as well as the changed button.
			if (Platform.OS === "ios") {
				void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
			}
		},
	});
	const clear = useMutation({
		...deleteFeedbackResponseMutation(),
		onSuccess: () => queryClient.setQueryData(responseKey, null),
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: responseKey });
			onChanged?.();
		},
	});

	let saveState: "idle" | "saving" | "error" = "idle";
	if (save.isPending || clear.isPending) {
		saveState = "saving";
	} else if (save.isError || clear.isError) {
		saveState = "error";
	}

	return {
		response,
		saveState,
		onSave: (body: ReplaceFeedbackResponseData["body"]) => save.mutate({ path, body }),
		onClear: () => clear.mutate({ path }),
	};
}
