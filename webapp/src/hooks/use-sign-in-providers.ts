import { useQuery } from "@tanstack/react-query";

import { listIdentityProvidersOptions } from "@/api/@tanstack/react-query.gen";
import type { SignInOptions } from "@/components/auth/SignInButtons";

/** Longer than `QUERY_STALE_TIME_MS`: discovery only changes when an operator reconfigures sign-in. */
const PROVIDER_STALE_TIME_MS = 5 * 60 * 1000;

export function useSignInProviders(enabled = true): SignInOptions {
	const query = useQuery({
		...listIdentityProvidersOptions(),
		staleTime: PROVIDER_STALE_TIME_MS,
		enabled,
	});
	// Data first: a failed background refetch must not replace usable buttons with an error.
	if (query.data) return { status: "ready", providers: query.data };
	if (query.isError) return { status: "error", onRetry: () => void query.refetch() };
	return { status: "loading" };
}
