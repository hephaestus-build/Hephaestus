import type { BrowserContext } from "@playwright/test";

import { getStartedRecordKey } from "@/components/onboarding/get-started-record";

/**
 * The start of every account's Get started record. The account that signs in is the server's, so a
 * test cannot name its record before the page loads.
 */
const RECORD = getStartedRecordKey("", "").slice(0, -1);

/** A returning reader: whoever signs in, they finished Get started, so no pill or card covers the page. */
export async function asReturningReader(context: BrowserContext): Promise<void> {
	await context.addInitScript((record) => {
		// oxlint-disable-next-line typescript/unbound-method -- the original is called below with the storage it was asked on.
		const read = Storage.prototype.getItem;
		Storage.prototype.getItem = function getItem(key) {
			return key.startsWith(record) ? "finished" : read.call(this, key);
		};
	}, RECORD);
}

/** A reader on their first visit: this browser holds no Get started record for any account. */
export async function asFirstVisit(context: BrowserContext): Promise<void> {
	await context.addInitScript((record) => {
		for (const key of Object.keys(localStorage)) {
			if (key.startsWith(record)) {
				localStorage.removeItem(key);
			}
		}
	}, RECORD);
}
