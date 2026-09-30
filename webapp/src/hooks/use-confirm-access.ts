import { useQueries } from "@tanstack/react-query";

import { listIdentityProvidersOptions } from "@/api/@tanstack/react-query.gen";
import { isSignInProvider } from "@/lib/sign-in-providers";
import { hasText } from "@/lib/text";
import { authClient } from "@/runtime/auth/auth-client";
import { currentUserQueryOptions } from "@/runtime/auth/guard";

/** `returnTo` is where signing in again lands; by default the page that asked. */
export function useConfirmAccess(enabled: boolean, returnTo?: string) {
	// The operator's own identity, not AuthContext's, which hides linked providers while viewing as
	// another user.
	const [instanceProviders, currentUser] = useQueries({
		queries: [
			{ ...listIdentityProvidersOptions(), enabled },
			{ ...currentUserQueryOptions(), enabled },
		],
	});

	// Both origins are canonical on the server, so a registration on another origin of the same
	// provider type — which would sign into a different account — never matches.
	const linked = currentUser.data?.linkedProviders ?? [];

	return {
		providers: (instanceProviders.data ?? []).filter(
			(provider) =>
				isSignInProvider(provider) &&
				hasText(provider.baseUrl) &&
				linked.some(
					(link) =>
						link.type?.toUpperCase() === provider.providerType?.toUpperCase() &&
						link.serverUrl === provider.baseUrl,
				),
		),
		loading: instanceProviders.isPending || currentUser.isPending,
		error: instanceProviders.isError || currentUser.isError,
		retry: () => {
			void instanceProviders.refetch();
			void currentUser.refetch();
		},
		signIn: (registrationId: string) => {
			authClient.login(
				registrationId,
				returnTo ?? `${window.location.pathname}${window.location.search}`,
			);
		},
	};
}
