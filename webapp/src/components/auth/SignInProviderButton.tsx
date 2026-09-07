import type { ComponentPropsWithoutRef } from "react";

import type { IdentityProviderView } from "@/api/types.gen";
import { GithubIcon, GitlabIcon } from "@/components/icons/brand";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type ButtonSize = ComponentPropsWithoutRef<typeof Button>["size"];

export function ProviderIcon({ provider }: { provider: IdentityProviderView }) {
	if (provider.providerType?.toUpperCase() === "GITHUB") {
		return <GithubIcon className="shrink-0" aria-hidden="true" focusable="false" />;
	}
	if (provider.providerType?.toUpperCase() === "GITLAB") {
		return <GitlabIcon className="shrink-0" aria-hidden="true" focusable="false" />;
	}
	return null;
}

export function SignInProviderButton({
	provider,
	onSignIn,
	disabled,
	size,
	className,
}: {
	provider: IdentityProviderView;
	onSignIn: (registrationId: string) => void;
	disabled?: boolean;
	size?: ButtonSize;
	className?: string;
}) {
	const registrationId = provider.registrationId ?? "";
	const label = provider.displayName ?? registrationId;
	return (
		<Button
			variant="outline"
			size={size}
			disabled={disabled}
			onClick={() => onSignIn(registrationId)}
			className={cn("h-auto min-h-9 w-full whitespace-normal", className)}
		>
			<ProviderIcon provider={provider} />
			<span className="min-w-0 wrap-anywhere">Continue with {label}</span>
		</Button>
	);
}
