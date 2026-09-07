export function SignInNotice() {
	return (
		<p className="text-xs text-muted-foreground leading-relaxed">
			Signing in shares your provider identity with this instance. Read the{" "}
			<a href="/privacy" target="_blank" rel="noreferrer" className="underline underline-offset-4">
				privacy notice (opens in a new tab)
			</a>
			. Research participation is a separate, optional choice after sign-in.
		</p>
	);
}
