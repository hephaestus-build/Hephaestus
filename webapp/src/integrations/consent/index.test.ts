import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
	CONSENT_STORAGE_KEY,
	CONSENT_VERSION,
	closeConsentReopen,
	getStoredConsent,
	hasErrorMonitoringConsent,
	isConsentReopenRequested,
	requestConsentReopen,
	setStoredConsent,
	subscribeConsent,
} from "./index";

describe("cookie consent", () => {
	beforeEach(() => {
		// getStoredConsent re-reads when the raw value changes, so clearing localStorage is enough to
		// reset the module-level snapshot cache between tests.
		localStorage.clear();
		getStoredConsent();
		closeConsentReopen(); // drain any reopen flag so it can't leak between tests
	});
	afterEach(() => localStorage.clear());

	it("returns null until a decision is recorded", () => {
		expect(getStoredConsent()).toBeNull();
	});

	it("stores only the configured error-monitoring choice at the current version", () => {
		setStoredConsent({ errorMonitoring: true });
		const stored = getStoredConsent();
		expect(stored).toMatchObject({ errorMonitoring: true, version: CONSENT_VERSION });
		expect(stored?.decidedAt).toBeTypeOf("string");
		expect(hasErrorMonitoringConsent()).toBe(true);
	});

	it("treats malformed or incomplete terminal storage as no decision", () => {
		localStorage.setItem(CONSENT_STORAGE_KEY, "not-json");
		expect(getStoredConsent()).toBeNull();
		localStorage.setItem(CONSENT_STORAGE_KEY, JSON.stringify({ decidedAt: "x" }));
		expect(getStoredConsent()).toBeNull();
	});

	// `"false"` is truthy, so reading this key for truthiness would turn error monitoring on for
	// someone who declined — and anything can write the key.
	it("treats a decision whose flags are not booleans as no decision, rather than taking it as given", () => {
		localStorage.setItem(
			CONSENT_STORAGE_KEY,
			JSON.stringify({ errorMonitoring: "true", decidedAt: "x", version: CONSENT_VERSION }),
		);

		expect(getStoredConsent()).toBeNull();
	});

	it("re-prompts (treats as no decision) when the stored consent version is older/missing", () => {
		localStorage.setItem(
			CONSENT_STORAGE_KEY,
			JSON.stringify({ errorMonitoring: true, decidedAt: "x" }), // no version
		);
		expect(getStoredConsent()).toBeNull();
		// Version 1 is the former consent surface, which still carried an analytics category.
		localStorage.setItem(
			CONSENT_STORAGE_KEY,
			JSON.stringify({ errorMonitoring: true, decidedAt: "x", version: CONSENT_VERSION - 1 }),
		);
		expect(getStoredConsent()).toBeNull();
	});

	it("returns a referentially stable snapshot while the raw value is unchanged", () => {
		// Guards the useSyncExternalStore getSnapshot contract: an unstable snapshot would crash the
		// consumer hook with an infinite render loop.
		setStoredConsent({ errorMonitoring: true });
		expect(getStoredConsent()).toBe(getStoredConsent());
	});

	it("returns a stable null snapshot before any decision exists", () => {
		// Also part of the getSnapshot contract: the no-decision answer must not be recomputed into
		// a fresh value on every read.
		expect(getStoredConsent()).toBeNull();
		expect(getStoredConsent()).toBe(getStoredConsent());
	});

	it("requestConsentReopen opens edit mode until the next decision is recorded", () => {
		setStoredConsent({ errorMonitoring: false });
		expect(isConsentReopenRequested()).toBe(false);

		requestConsentReopen();
		expect(isConsentReopenRequested()).toBe(true);

		// Saving (or cancelling) closes edit mode.
		setStoredConsent({ errorMonitoring: false });
		expect(isConsentReopenRequested()).toBe(false);
	});

	it("closes edit mode even when the decision cannot be persisted", () => {
		// With localStorage blocked (private mode, quota) the banner must still close and
		// subscribers must still re-read; the decision is simply not persisted.
		requestConsentReopen();
		const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
			throw new DOMException("QuotaExceededError");
		});
		try {
			setStoredConsent({ errorMonitoring: true });
		} finally {
			setItem.mockRestore();
		}
		expect(isConsentReopenRequested()).toBe(false);
	});

	it("a passive first visit does not request reopen", () => {
		expect(isConsentReopenRequested()).toBe(false);
	});

	it("notifies subscribers on set and on reopen", () => {
		const listener = vi.fn();
		const unsubscribe = subscribeConsent(listener);
		setStoredConsent({ errorMonitoring: false });
		requestConsentReopen();
		expect(listener).toHaveBeenCalledTimes(2);
		unsubscribe();
		setStoredConsent({ errorMonitoring: false });
		expect(listener).toHaveBeenCalledTimes(2);
	});
});
