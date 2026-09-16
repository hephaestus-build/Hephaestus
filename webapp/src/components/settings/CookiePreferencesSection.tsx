import { Button } from "@/components/ui/button";
import {
	errorMonitoringConfigured,
	requestConsentReopen,
	useCookieConsent,
} from "@/runtime/consent";

/**
 * Lets a signed-in user revisit the cookie choice they made in the consent banner. "Change cookie
 * choices" re-opens the banner (pre-seeded, cancelable), which is the withdrawal path GDPR Art. 7(3)
 * requires to be as easy as granting consent. Only the optional categories configured in this
 * deployment are summarised; {@code SettingsPage} hides this section entirely when none are.
 */
export function CookiePreferencesSection() {
	const consent = useCookieConsent();

	const parts: string[] = [];
	if (errorMonitoringConfigured) {
		parts.push(`Error reports ${consent?.errorMonitoring === true ? "on" : "off"}`);
	}
	const summary = consent && parts.length > 0 ? parts.join(" · ") : "Using essential cookies only.";

	return (
		<section className="space-y-4" aria-labelledby="cookie-preferences-heading">
			<div className="space-y-1">
				<h2 id="cookie-preferences-heading" className="text-xl font-semibold">
					Privacy
				</h2>
				<p className="text-sm text-muted-foreground">
					Essential cookies keep you signed in and secure. You can change your choice about optional
					cookies anytime.
				</p>
			</div>
			<div className="flex items-center justify-between gap-4">
				<p className="text-sm text-muted-foreground">{summary}</p>
				<Button variant="outline" className="shrink-0" onClick={() => requestConsentReopen()}>
					Change cookie choices
				</Button>
			</div>
		</section>
	);
}
