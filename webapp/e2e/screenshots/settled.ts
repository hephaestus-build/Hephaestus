import { expect, type Page } from "@playwright/test";

/** The whole wait, below every test's own timeout, so that a failure names what still blocks. */
const SETTLE_MS = 45_000;

/**
 * Waits until the page shows its content: the app marks the document idle (`usePageIdle` in
 * `src/runtime/page-idle.ts`: no first load, no write and no route load pending), and its fonts and
 * eager images are ready for the scan. A lazy image below the fold loads only when the page scrolls
 * to it. On timeout it names what the last check found.
 *
 * Not `networkidle`: that needs 500 ms with no request at all, which a poll can deny for the whole
 * test, and its timeout names no cause. The app knows when it has loaded; the network does not.
 */
export async function settled(page: Page): Promise<void> {
	await expect
		.poll(async () => blockers(page), {
			message: "the page shows its content",
			timeout: SETTLE_MS,
		})
		.toEqual([]);
}

async function blockers(page: Page): Promise<string[]> {
	try {
		return await page.evaluate(() => {
			const found: string[] = [];
			if (document.documentElement.dataset.pageIdle !== "true") {
				found.push("the app is still loading");
			}
			if (document.fonts.status !== "loaded") {
				found.push("fonts are still loading");
			}
			for (const image of document.images) {
				if (!image.complete && image.loading !== "lazy") {
					found.push(`image still loading: ${image.currentSrc || image.src}`);
				}
			}
			return found;
		});
	} catch (error) {
		// A page that redirects replaces its document while it is read.
		if (error instanceof Error && error.message.includes("Execution context was destroyed")) {
			return ["the page navigated"];
		}
		throw error;
	}
}
