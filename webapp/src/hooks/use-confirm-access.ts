import { useQueries } from "@tanstack/react-query";

import {
	listAccountIdentityProvidersOptions,
	listLinkedIdentitiesOptions,
} from "@/api/@tanstack/react-query.gen";
import { authClient } from "@/integrations/auth/auth-client";
import { isSignInProvider } from "@/lib/sign-in-providers";

export function useConfirmAccess(enabled: boolean) {
	const [instanceProviders, linkedIdentities] = useQueries({
		queries: [
			{ ...listAccountIdentityProvidersOptions(), enabled },
			{ ...listLinkedIdentitiesOptions(), enabled },
		],
	});

	return {
		providers: (instanceProviders.data ?? []).filter(
			(provider) =>
				isSignInProvider(provider) &&
				(linkedIdentities.data ?? []).some(
					(identity) =>
						identity.providerType === provider.providerType &&
						Boolean(identity.serverUrl) &&
						identity.serverUrl === provider.baseUrl,
				),
		),
		loading: instanceProviders.isPending || linkedIdentities.isPending,
		error: instanceProviders.isError || linkedIdentities.isError,
		retry: () => {
			void instanceProviders.refetch();
			void linkedIdentities.refetch();
		},
		signIn: (registrationId: string) => {
			authClient.login(registrationId, `${window.location.pathname}${window.location.search}`);
		},
	};
}
