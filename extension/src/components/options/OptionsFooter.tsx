import { ExternalLink } from "~/components/common/ExternalLink";

export interface OptionsFooterProps {
	helpUrl: string;
	privacyUrl: string;
	/** The only origin the two links may point to. */
	docsOrigin: string;
	version?: string;
}

/** Help and the extension's data handling notice, reachable before and after signing in. */
export function OptionsFooter({ helpUrl, privacyUrl, docsOrigin, version }: OptionsFooterProps) {
	return (
		<footer className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2 border-t border-border pt-4 text-xs text-muted-foreground">
			<nav aria-label="Help and privacy" className="flex flex-wrap gap-x-4 gap-y-2">
				<ExternalLink href={helpUrl} allowedOrigin={docsOrigin}>
					Help
				</ExternalLink>
				<ExternalLink href={privacyUrl} allowedOrigin={docsOrigin}>
					Privacy
				</ExternalLink>
			</nav>
			{version === undefined ? null : <p>Hephaestus for Chrome {version}</p>}
		</footer>
	);
}
