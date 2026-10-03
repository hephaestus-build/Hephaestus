import type { IdentityProviderView } from "@/api/types.gen";
import { GitHubIcon, GitLabIcon } from "@/components/icons/brand";
import { Button } from "@/components/ui/button";

function ProviderIcon({ provider }: { provider: IdentityProviderView }) {
	if (provider.providerType?.toUpperCase() === "GITHUB") {
		return <GitHubIcon className="shrink-0" />;
	}
	if (provider.providerType?.toUpperCase() === "GITLAB") {
		return <GitLabIcon className="shrink-0" />;
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
