import { LegalLink } from "@/components/auth/LegalLinks";

export function SignInNotice() {
	return (
		<p className="text-xs leading-relaxed text-muted-foreground">
			Signing in shares your provider identity with this instance — see the{" "}
			<LegalLink to="/privacy">privacy notice</LegalLink> and the{" "}
			<LegalLink to="/imprint">imprint</LegalLink>.
		</p>
	);
}
