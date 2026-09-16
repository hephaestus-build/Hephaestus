import { useSyncExternalStore } from "react";
import { z } from "zod";

import { hasText } from "@/lib/text";
import { isSentryConfigured } from "@/runtime/sentry/config";

export const CONSENT_STORAGE_KEY = "hephaestus-cookie-consent";

export const errorMonitoringConfigured = isSentryConfigured;

export const optionalIntegrationsAvailable = errorMonitoringConfigured;

/** Increment when the consent categories or their purposes change. */
export const CONSENT_VERSION = 2;

export interface CookieConsent {
	errorMonitoring: boolean;
	decidedAt: string;
	version: number;
}

export type ConsentChoice = Pick<CookieConsent, "errorMonitoring">;

type ConsentListener = () => void;

const listeners = new Set<ConsentListener>();

function emitChange() {
	for (const listener of listeners) {
		listener();
	}
}

// `useSyncExternalStore` re-renders every commit unless `getSnapshot` returns a referentially stable
// value, so the parsed object is memoised by the raw string it was parsed from.
let cachedRaw: string | null = null;
let cachedConsent: CookieConsent | null = null;
let cacheInitialized = false;

const storedConsentSchema = z.object({
	errorMonitoring: z.boolean(),
	decidedAt: z.string().optional(),
	version: z.number(),
});

function parseConsent(raw: string): CookieConsent | null {
	let json: unknown;
	try {
		json = JSON.parse(raw);
	} catch {
		return null;
	}
	const parsed = storedConsentSchema.safeParse(json);
	if (!parsed.success || parsed.data.version !== CONSENT_VERSION) {
		return null;
	}
	return {
		errorMonitoring: parsed.data.errorMonitoring,
		decidedAt: parsed.data.decidedAt ?? new Date().toISOString(),
		version: CONSENT_VERSION,
	};
}

export function getStoredConsent(): CookieConsent | null {
	if (typeof window === "undefined") {
		return null;
	}
	let raw: string | null;
	try {
		raw = window.localStorage.getItem(CONSENT_STORAGE_KEY);
	} catch {
		return null;
	}
	if (cacheInitialized && raw === cachedRaw) {
		return cachedConsent;
	}
	cacheInitialized = true;
	cachedRaw = raw;
	cachedConsent = hasText(raw) ? parseConsent(raw) : null;
	return cachedConsent;
}

export function setStoredConsent(consent: ConsentChoice) {
	if (typeof window === "undefined") {
		return;
	}
	const value: CookieConsent = {
		errorMonitoring: consent.errorMonitoring,
		decidedAt: new Date().toISOString(),
		version: CONSENT_VERSION,
	};
	try {
		window.localStorage.setItem(CONSENT_STORAGE_KEY, JSON.stringify(value));
	} catch {
		// localStorage may be unavailable (private mode / disabled); fall through so the banner
		// still closes and subscribers re-read — the decision is simply not persisted.
	}
	closeConsentReopen();
	emitChange();
}

let reopenRequested = false;

export function requestConsentReopen() {
	reopenRequested = true;
	emitChange();
}

export function closeConsentReopen() {
	if (reopenRequested) {
		reopenRequested = false;
		emitChange();
	}
}

export function isConsentReopenRequested(): boolean {
	return reopenRequested;
}

/** Fires for a reopen or a decision in this tab, and for a decision in any other tab. */
export function subscribeConsent(listener: ConsentListener): () => void {
	listeners.add(listener);
	const onStorage = (event: StorageEvent) => {
		if (event.key === CONSENT_STORAGE_KEY) {
			listener();
		}
	};
	window.addEventListener("storage", onStorage);
	return () => {
		listeners.delete(listener);
		window.removeEventListener("storage", onStorage);
	};
}

export function useCookieConsent(): CookieConsent | null {
	return useSyncExternalStore(subscribeConsent, getStoredConsent, () => null);
}

export function useConsentReopenRequested(): boolean {
	return useSyncExternalStore(subscribeConsent, isConsentReopenRequested, () => false);
}

export function hasErrorMonitoringConsent(): boolean {
	return getStoredConsent()?.errorMonitoring === true;
}
