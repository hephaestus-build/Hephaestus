import { cn } from "cn";
import { LegalLink } from "@/components/auth/LegalLinks";

export function SignInNotice({ className }: { className?: string }) {
	return (
		<p className={cn("text-xs leading-relaxed text-muted-foreground", className)}>
			Signing in shares your provider identity with this instance — see the{" "}
			<LegalLink to="/privacy">privacy notice</LegalLink> and the{" "}
			<LegalLink to="/imprint">imprint</LegalLink>.
		</p>
	);
}
