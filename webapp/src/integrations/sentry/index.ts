import * as Sentry from "@sentry/react";

import environment from "@/environment";
import { hasErrorMonitoringConsent } from "@/integrations/consent";
import { sentryDsn, sentryEnvironment } from "@/integrations/sentry/config";

let initialized = false;

type DataCollection = NonNullable<NonNullable<Parameters<typeof Sentry.init>[0]>["dataCollection"]>;

// Keep the SDK's collection inventory exhaustive so additions require an explicit decision.
export const DATA_COLLECTION = {
	userInfo: false,
	cookies: false,
	httpHeaders: { request: false, response: false },
	httpBodies: [],
	urlQueryParams: false,
	graphQL: { document: false, variables: false },
	genAI: { inputs: false, outputs: false },
	databaseQueryData: false,
	stackFrameVariables: false,
	frameContextLines: 5,
} satisfies Required<Omit<DataCollection, "queryParams">>;

const isUnsubscribePage = () => /^\/unsubscribe\/?$/.test(window.location.pathname);

export function stripRequestUserAndBreadcrumbs(event: Sentry.ErrorEvent): Sentry.ErrorEvent | null {
	// Bearer-link confirmation stays untracked even if monitoring was enabled on a previous page.
	if (isUnsubscribePage()) return null;
	delete event.user;
	delete event.request;
	delete event.breadcrumbs;
	return event;
}

export function captureException(error: unknown) {
	if (initialized) Sentry.captureException(error);
}

export function initSentry() {
	if (initialized) {
		return;
	}
	if (!sentryDsn || !hasErrorMonitoringConsent() || isUnsubscribePage()) {
		return;
	}
	Sentry.init({
		dsn: sentryDsn,
		environment: sentryEnvironment,
		release: environment.version,
		dataCollection: DATA_COLLECTION,
		beforeSend: stripRequestUserAndBreadcrumbs,
	});
	initialized = true;
}

export function disableSentry() {
	if (!initialized) {
		return;
	}
	void Sentry.getClient()?.close();
	initialized = false;
}
