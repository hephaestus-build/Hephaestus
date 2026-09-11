import { useQueries } from "@tanstack/react-query";

import {
	listIdentityProvidersOptions,
	listLinkedIdentitiesOptions,
} from "@/api/@tanstack/react-query.gen";
import { authClient } from "@/integrations/auth/auth-client";
import { isSignInProvider } from "@/lib/sign-in-providers";

export function useConfirmAccess(enabled: boolean) {
	const [instanceProviders, linkedIdentities] = useQueries({
		queries: [
			{ ...listIdentityProvidersOptions(), enabled },
			{ ...listLinkedIdentitiesOptions(), enabled },
		],
	});

	const linkedTypes = new Set(
		(linkedIdentities.data ?? []).flatMap((identity) =>
			identity.providerType ? [identity.providerType.toUpperCase()] : [],
		),
	);

	return {
		providers: (instanceProviders.data ?? []).filter(
			(provider) =>
				isSignInProvider(provider) && linkedTypes.has(provider.providerType?.toUpperCase() ?? ""),
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
