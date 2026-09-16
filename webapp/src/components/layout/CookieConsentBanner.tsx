import { Link } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

import { ConsentBanner } from "@/components/layout/ConsentBanner";
import {
	closeConsentReopen,
	errorMonitoringConfigured,
	optionalIntegrationsAvailable,
	setStoredConsent,
	useConsentReopenRequested,
	useCookieConsent,
} from "@/integrations/consent";

export function CookieConsentBanner() {
	const consent = useCookieConsent();
	const reopen = useConsentReopenRequested();
	if (!optionalIntegrationsAvailable || (consent !== null && !reopen)) {
		return null;
	}
	return <ConsentForm editing={consent !== null} reopened={reopen} />;
}

function ConsentForm({ editing, reopened }: { editing: boolean; reopened: boolean }) {
	const cardRef = useRef<HTMLDivElement>(null);
	useEffect(() => {
		if (reopened) {
			cardRef.current?.focus();
		}
	}, [reopened]);

	const restoreFocus = () => document.querySelector<HTMLElement>("main")?.focus();
	const decide = (errorMonitoring: boolean) => {
		setStoredConsent({ errorMonitoring });
		restoreFocus();
	};
	const cancel = () => {
		closeConsentReopen();
		restoreFocus();
	};
	return (
		<ConsentBanner
			ref={cardRef}
			editing={editing}
			onAllow={() => decide(errorMonitoringConfigured)}
			onDecline={() => decide(false)}
			onCancel={cancel}
			privacyPolicy={
				<Link to="/privacy" className="underline underline-offset-4 hover:text-foreground">
					Read our Privacy Policy
				</Link>
			}
		/>
	);
}
