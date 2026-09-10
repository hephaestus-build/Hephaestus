import { LegalLinks } from "@/components/auth/LegalLinks";

export function SignInNotice() {
	return (
		<div className="space-y-2">
			<p className="text-xs leading-relaxed text-muted-foreground">
				Signing in shares your provider identity with this Hephaestus instance.
			</p>
			<LegalLinks />
		</div>
	);
}
