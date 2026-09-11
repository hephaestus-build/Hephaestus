import type { IdentityProviderView } from "@/api/types.gen";
import { GithubIcon, GitlabIcon } from "@/components/icons/brand";
import { Button } from "@/components/ui/button";

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
}: {
	provider: IdentityProviderView;
	onSignIn: (registrationId: string) => void;
}) {
	const registrationId = provider.registrationId ?? "";
	const label = provider.displayName ?? registrationId;
	return (
		<Button
			variant="outline"
			onClick={() => onSignIn(registrationId)}
			className="h-auto min-h-9 w-full whitespace-normal"
		>
			<ProviderIcon provider={provider} />
			<span className="min-w-0 wrap-anywhere">Continue with {label}</span>
		</Button>
	);
}
