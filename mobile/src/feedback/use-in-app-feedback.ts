import { useQuery } from "@tanstack/react-query";
import { useFocusEffect, useIsFocused } from "expo-router";
import { useCallback, useEffect } from "react";
import { AppState } from "react-native";

import { getInAppFeedbackOptions } from "@/api/@tanstack/react-query.gen";

/**
 * The developer's practice feedback. The server records a piece of feedback as delivered when this
 * list is read, so it is read only while the screen showing it is in front of the person: on focus,
 * on pull-to-refresh, and when the app returns to the foreground on this screen. Never in the
 * background, never on a reconnect elsewhere, never ahead of time from a notification.
 */
export function useInAppFeedback(workspaceSlug: string) {
	const query = useQuery({
		...getInAppFeedbackOptions({ path: { workspaceSlug } }),
		refetchOnWindowFocus: false,
		refetchOnReconnect: false,
		refetchOnMount: false,
		staleTime: Number.POSITIVE_INFINITY,
		enabled: false,
	});
	const { refetch } = query;
	const focused = useIsFocused();

	useFocusEffect(
		useCallback(() => {
			void refetch();
		}, [refetch]),
	);

	useEffect(() => {
		if (!focused) {
			return;
		}
		const subscription = AppState.addEventListener("change", (status) => {
			if (status === "active") {
				void refetch();
			}
		});
		return () => subscription.remove();
	}, [focused, refetch]);

	return query;
}
