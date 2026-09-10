import { LegalLink } from "@/components/auth/LegalLinks";

/**
 * Sign-in is reached with no site footer behind it on `/login`, and with the footer covered when the
 * dialog is open, so the two links a visitor is entitled to before a provider redirect are in the
 * sentence itself rather than in chrome neither surface has.
 */
export function SignInNotice() {
	return (
		<p className="text-xs leading-relaxed text-muted-foreground">
			Signing in shares your provider identity with this instance — see the{" "}
			<LegalLink href="/privacy">privacy notice</LegalLink> and the{" "}
			<LegalLink href="/imprint">imprint</LegalLink>.
		</p>
	);
}
