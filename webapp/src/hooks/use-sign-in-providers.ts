import { useQuery } from "@tanstack/react-query";

import { listIdentityProvidersOptions } from "@/api/@tanstack/react-query.gen";
import type { SignInOptions } from "@/components/auth/SignInButtons";

/** Discovery changes only when an operator reconfigures sign-in. */
const PROVIDER_STALE_TIME_MS = 5 * 60 * 1000;

export function useSignInProviders(enabled = true): SignInOptions {
	const query = useQuery({
		...listIdentityProvidersOptions(),
		staleTime: PROVIDER_STALE_TIME_MS,
		enabled,
	});
	if (query.isLoadingError) return { status: "error", onRetry: () => void query.refetch() };
	if (query.data) return { status: "ready", providers: query.data };
	return { status: "loading" };
}
