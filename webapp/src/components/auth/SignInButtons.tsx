import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { LogInIcon } from "lucide-react";
import type { ComponentPropsWithoutRef } from "react";

import { listIdentityProvidersOptions } from "@/api/@tanstack/react-query.gen";
import type { IdentityProviderView } from "@/api/types.gen";
import { DevSignInForm } from "@/components/auth/DevSignInForm";
import { ProviderIcon, SignInProviderButton } from "@/components/auth/SignInProviderButton";
import { Button, buttonVariants } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { DEV_PROVIDER_TYPE, isSignInProvider } from "@/lib/sign-in-providers";
import { cn } from "@/lib/utils";

type ButtonSize = ComponentPropsWithoutRef<typeof Button>["size"];

interface SignInButtonsProps {
	onSignIn: (idpHint: string) => void;
	disabled?: boolean;
	size?: ButtonSize;
	className?: string;
	/** Header mode: compact buttons (icon + short name on desktop, icon-only on mobile). */
	header?: boolean;
	/** Destination after a successful dev sign-in (full mode only). Defaults to home. */
	devReturnTo?: string;
}

function HeaderProviderButton({
	provider,
	onSignIn,
	disabled,
}: {
	provider: IdentityProviderView;
	onSignIn: (idpHint: string) => void;
	disabled?: boolean;
}) {
	const registrationId = provider.registrationId ?? "";
	const label = provider.displayName ?? registrationId;
	return (
		<Tooltip>
			<TooltipTrigger
				render={
					// Below `sm` the label is hidden and the mark is aria-hidden, so without this the button
					// has NO accessible name (a tooltip does not supply one). Name it explicitly.
					<Button
						variant="outline"
						disabled={disabled}
						aria-label={`Continue with ${label}`}
						onClick={() => onSignIn(registrationId)}
					/>
				}
			>
				<ProviderIcon provider={provider} />
				<span className="hidden sm:inline">{label}</span>
			</TooltipTrigger>
			<TooltipContent className="sm:hidden">Continue with {label}</TooltipContent>
		</Tooltip>
	);
}

/**
 * Renders a stock shadcn `outline` sign-in button per enabled identity provider (brand icon for
 * recognition + "Continue with …" label). The provider list is fetched from `/identity-providers`.
 *
 * - Default: full-width buttons stacked vertically (the login card).
 * - `header`: compact buttons (icon + short name on desktop, icon-only on mobile).
 */
export function SignInButtons({
	onSignIn,
	disabled,
	size,
	className,
	header,
	devReturnTo,
}: SignInButtonsProps) {
	const {
		data: providers,
		isLoading,
		isError,
	} = useQuery({
		...listIdentityProvidersOptions(),
		staleTime: 5 * 60 * 1000,
	});

	// On a genuine discovery failure we must NOT imply a specific provider works (this instance may be
	// GitLab-only, where a GitHub button leads to a dead OAuth path). Show a neutral, non-misleading state.
	if (isError || (!isLoading && !providers)) {
		if (header) {
			return <span className="text-sm text-muted-foreground">Sign-in unavailable</span>;
		}
		return (
			<p className={cn("text-center text-sm text-muted-foreground", className)}>
				Couldn't load sign-in options. Please refresh and try again.
			</p>
		);
	}

	// While the list loads, show a NON-clickable placeholder — never an optimistic provider button.
	if (isLoading || !providers) {
		if (header) {
			return (
				<Button variant="outline" disabled aria-label="Loading sign-in options">
					<Spinner aria-hidden="true" />
				</Button>
			);
		}
		return (
			<Button
				variant="outline"
				size={size}
				disabled
				aria-label="Loading sign-in options"
				className={cn("w-full", className)}
			>
				<Spinner aria-hidden="true" />
				Loading sign-in options…
			</Button>
		);
	}

	// The dev sign-in needs a username field, so it renders as a small form (full mode only), never as
	// an OAuth-style button — and it is excluded from the compact header entirely.
	const oauthProviders = providers.filter(isSignInProvider);
	const hasDevSignIn = providers.some(
		(provider) => provider.providerType?.toUpperCase() === DEV_PROVIDER_TYPE,
	);

	if (header) {
		// The dev sign-in needs a username, and a link-only provider is not a way in — so with neither
		// GitHub nor GitLab configured there is nothing the header can render inline. It must still
		// offer a way in: send the user to the full sign-in page rather than showing an empty header.
		if (oauthProviders.length === 0) {
			if (disabled) {
				return (
					<Button variant="outline" size="sm" disabled aria-label="Sign in">
						<LogInIcon aria-hidden />
						Sign in
					</Button>
				);
			}
			return (
				<Link
					to="/login"
					className={buttonVariants({ variant: "outline", size: "sm" })}
					aria-label="Sign in"
				>
					<LogInIcon aria-hidden />
					Sign in
				</Link>
			);
		}
		return (
			<div className="flex items-center gap-2">
				{oauthProviders.map((provider) => (
					<HeaderProviderButton
						key={provider.registrationId ?? provider.displayName}
						provider={provider}
						onSignIn={onSignIn}
						disabled={disabled}
					/>
				))}
			</div>
		);
	}

	return (
		<div className="flex flex-col gap-2">
			{oauthProviders.map((provider) => (
				<SignInProviderButton
					key={provider.registrationId ?? provider.displayName}
					provider={provider}
					onSignIn={onSignIn}
					disabled={disabled}
					size={size}
					className={className}
				/>
			))}
			{hasDevSignIn ? <DevSignInForm returnTo={devReturnTo} /> : null}
		</div>
	);
}
