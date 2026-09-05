import { useQueries } from "@tanstack/react-query";

import {
	listIdentityProvidersOptions,
	listLinkedIdentitiesOptions,
} from "@/api/@tanstack/react-query.gen";
import { authClient } from "@/integrations/auth/auth-client";
import { isSignInProvider } from "@/lib/sign-in-providers";

/**
 * Everything `ConfirmAccessDialog` needs to offer a fresh sign-in on the page that was refused.
 *
 * The registrations it returns are what the instance offers as a way in, narrowed to the provider
 * types this account is already linked to. That intersection is the whole point: a sign-in with a
 * provider the account has never linked resolves — or provisions — a *different* account, so
 * offering the instance's full list would throw an operator out of the session they were proving.
 *
 * Neither list is fetched until `enabled`, because nothing asks for them until an action has
 * already been refused.
 */
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
		/**
		 * The redirect lands back where the refusal happened, so the operator retries the action
		 * they were already on rather than being dropped at the instance's home page.
		 */
		signIn: (registrationId: string) => {
			authClient.login(registrationId, `${window.location.pathname}${window.location.search}`);
		},
	};
}
