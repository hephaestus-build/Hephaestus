import { useQuery } from "@tanstack/react-query";

import { listIdentityProvidersOptions } from "@/api/@tanstack/react-query.gen";
import type { SignInOptions } from "@/components/auth/SignInButtons";

export function useSignInProviders(enabled = true): SignInOptions {
	const query = useQuery({ ...listIdentityProvidersOptions(), staleTime: 5 * 60 * 1000, enabled });
	if (query.isError) return { status: "error", onRetry: () => void query.refetch() };
	if (!query.data) return { status: "loading" };
	return { status: "ready", providers: query.data };
}
