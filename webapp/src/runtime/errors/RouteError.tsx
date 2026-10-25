import { type ErrorComponentProps, useRouter } from "@tanstack/react-router";

import { PageError, type PageErrorProps } from "@/components/common/PageError";
import { problemIsRetryable, problemStatusOf } from "@/lib/problem-detail";

/**
 * What a route shows when its `beforeLoad`, loader or component throws. It names the cause when the
 * error carries a status, and offers Retry only for a failure that running the route again can fix.
 * The error's own message is never shown: it can carry data the reader should not see.
 */
export function RouteError({ error }: Pick<ErrorComponentProps, "error">) {
	const router = useRouter();
	const status = problemStatusOf(error);
	let state: PageErrorProps;
	if (status === 404) {
		state = { cause: "not-found" };
	} else if (status === 403) {
		state = { cause: "no-access" };
	} else if (problemIsRetryable(error)) {
		// `invalidate` loads the route again and resets the boundary; `reset` alone would only
		// render it again with the loader's error still in place.
		state = {
			cause: "failed",
			onRetry: () => {
				void router.invalidate();
			},
		};
	} else {
		state = { cause: "failed" };
	}
	// `PageError` owns the rule: an error is an alert, a missing page is not.
	return state.cause === "not-found" ? (
		<PageError {...state} />
	) : (
		<div role="alert" className="flex flex-1 flex-col">
			<PageError {...state} />
		</div>
	);
}
