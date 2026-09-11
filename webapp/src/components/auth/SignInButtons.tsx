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
	devReturnTo?: string;
}

export function SignInButtons({ options, onSignIn, devReturnTo }: SignInButtonsProps) {
	if (options.status === "error") {
		return (
			<div className="space-y-3">
				<p role="alert">We couldn't load the sign-in options.</p>
				<Button variant="outline" onClick={options.onRetry}>
					Try again
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
				No sign-in options are configured. Contact this instance's operator.
			</p>
		);
	}
	return (
		<div className="flex flex-col gap-2">
			{oauthProviders.map((provider) => (
				<SignInProviderButton
					key={provider.registrationId}
					provider={provider}
					onSignIn={onSignIn}
				/>
			))}
			{hasDevSignIn && <DevSignInForm returnTo={devReturnTo} />}
		</div>
	);
}
