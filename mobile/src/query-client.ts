import { focusManager, QueryClient } from "@tanstack/react-query";
import { AppState } from "react-native";

import { statusOf } from "@/session/api-client";

// React Native has no window focus; the app coming to the foreground is its equivalent. There is no
// online manager: a request made offline fails, and the screen says so and offers to try again.
focusManager.setEventListener((setFocused) => {
	const subscription = AppState.addEventListener("change", (status) => {
		setFocused(status === "active");
	});
	return () => {
		subscription.remove();
	};
});

/** A 4xx other than an expired token will answer the same the next time; retrying only delays saying so. */
function retry(failureCount: number, error: unknown): boolean {
	const status = statusOf(error);
	if (
		status !== undefined &&
		status >= 400 &&
		status < 500 &&
		status !== 401 &&
		status !== 408 &&
		status !== 429
	) {
		return false;
	}
	return failureCount < 2;
}

function createQueryClient(): QueryClient {
	return new QueryClient({
		defaultOptions: {
			queries: { staleTime: 30_000, retry },
			mutations: { retry: false },
		},
	});
}

let active = createQueryClient();

/** The cache of the current session. */
export function activeQueryClient(): QueryClient {
	return active;
}

/**
 * Gives the next session a cache of its own. The previous one's requests in flight are cancelled and
 * its data dropped, so nothing one account saw can be shown to — or late-filled into — the next.
 */
export function replaceQueryClient(): void {
	const previous = active;
	active = createQueryClient();
	void previous.cancelQueries();
	previous.clear();
}
