import type { IdentityProviderView } from "@/api/types.gen";
import { DevSignInForm } from "@/components/auth/DevSignInForm";
import { SignInProviderButton } from "@/components/auth/SignInProviderButton";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { DEV_PROVIDER_TYPE, isSignInProvider } from "@/lib/sign-in-providers";

export type SignInOptions =
	| { status: "loading" }
	| { status: "error"; onRetry: () => void }
	| { status: "ready"; providers: IdentityProviderView[] };

export interface SignInButtonsProps {
	options: SignInOptions;
	onSignIn: (registrationId: string) => void;
	/** Rejects when the server refuses the session; the form shows the message. */
	onDevSignIn: (username: string) => Promise<void>;
}

export function SignInButtons({ options, onSignIn, onDevSignIn }: SignInButtonsProps) {
	if (options.status === "error") {
		return (
			<div className="space-y-3">
				<p role="alert">We could not load the sign-in options.</p>
				<Button variant="outline" onClick={options.onRetry}>
					Retry
				</Button>
			</div>
		);
	}
	if (options.status === "loading") {
		// `aria-label` on a plain container is a prohibited attribute; the name has to be real text.
		return (
			<div className="flex flex-col gap-2" aria-busy="true">
				<span className="sr-only">Loading sign-in options…</span>
				<Skeleton className="h-9 w-full" />
				<Skeleton className="h-9 w-full" />
			</div>
		);
	}
	const { providers } = options;
	const oauthProviders = providers.filter(isSignInProvider);
	const hasDevSignIn = providers.some(
		(provider) => provider.providerType?.toUpperCase() === DEV_PROVIDER_TYPE,
	);
	if (oauthProviders.length === 0 && !hasDevSignIn) {
		return (
			<p className="text-sm text-muted-foreground">
				No sign-in options are configured. Contact your instance operator.
			</p>
		);
	}
	// A signed-out sign-in through single sign-on creates a new account, and accounts are never joined by
	// email, so a person who already has one connects it from Settings instead.
	const singleSignOn = oauthProviders.filter(
		(provider) => provider.providerType?.toUpperCase() === "OIDC",
	);
	const otherWayIn = oauthProviders.length > singleSignOn.length;
	return (
		<div className="flex flex-col gap-2">
			{oauthProviders.map((provider) => (
				<SignInProviderButton
					key={provider.registrationId}
					provider={provider}
					onSignIn={onSignIn}
				/>
			))}
			{hasDevSignIn && <DevSignInForm onSignIn={onDevSignIn} />}
			{singleSignOn.length > 0 && otherWayIn && (
				<p className="text-sm text-muted-foreground">
					Already use Hephaestus? Sign in as before, then connect{" "}
					{singleSignOn
						.map((provider) => provider.displayName ?? provider.registrationId)
						.join(" or ")}{" "}
					in Settings.
				</p>
			)}
		</div>
	);
}
