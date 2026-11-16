import type { BrowserContext, Page } from "@playwright/test";

import { tourRecordKey } from "@/components/tour/tour-start";

/**
 * The start of every account's record that the Practice profile offered its tour. The account that
 * signs in is the server's, so a test cannot name its record before the page loads.
 */
const OFFERED = tourRecordKey("offered", "");

/** A returning reader: whoever signs in, the Practice profile has already offered its tour. */
export async function asReturningTourReader(context: BrowserContext): Promise<void> {
	await context.addInitScript((offered) => {
		// oxlint-disable-next-line typescript/unbound-method -- the original is called below with the storage it was asked on.
		const read = Storage.prototype.getItem;
		Storage.prototype.getItem = function getItem(key) {
			return key.startsWith(offered) ? "1" : read.call(this, key);
		};
	}, OFFERED);
}

/** A reader on their first visit: no account has been offered the tour in this browser. */
export async function asFirstTourVisit(context: BrowserContext): Promise<void> {
	await context.addInitScript((offered) => {
		for (const key of Object.keys(localStorage)) {
			if (key.startsWith(offered)) {
				localStorage.removeItem(key);
			}
		}
	}, OFFERED);
}

/** The records of an offer that this browser keeps, one for each account that was offered the tour. */
export async function tourOfferRecords(page: Page): Promise<string[]> {
	return page.evaluate(
		(offered) =>
			Object.keys(localStorage).filter(
				(key) => key.startsWith(offered) && localStorage.getItem(key) === "1",
			),
		OFFERED,
	);
}
