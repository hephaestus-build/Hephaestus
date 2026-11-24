import { expect, type Page } from "@playwright/test";

/**
 * What the page shows while its data loads: a skeleton, or a region that says it is busy
 * (`webapp/DESIGN.md` § Loading and errors).
 */
const LOADING = '[data-slot="skeleton"], [aria-busy="true"]';

/** How long the document must stay unchanged to count as rendered. */
const QUIET_MS = 300;

/**
 * Waits until the page shows its content: nothing still loads, every image and font is ready, and the
 * document has stopped changing. The test's own timeout bounds each wait.
 *
 * Not `networkidle`: that waits for 500 ms without a request, which a poll or a held request can deny
 * for the whole test, and then the timeout names no cause. A request that never ends keeps its
 * skeleton here, so the wait fails on what the reader sees, and the trace shows the open request.
 */
export async function settled(page: Page): Promise<void> {
	await expect(page.locator(LOADING), "the page loads its data").toHaveCount(0, { timeout: 0 });
	await page.waitForFunction(
		async () => {
			await document.fonts.ready;
			return [...document.images].every((image) => image.complete);
		},
		undefined,
		{ timeout: 0 },
	);
	// A render that the last answer starts can land after the placeholders went away. Timers, not a
	// clock: a screenshot test fixes the page's clock, and its timers still run.
	await page.evaluate(async (quietMs) => {
		const { promise, resolve } = Promise.withResolvers<undefined>();
		const done = () => {
			observer.disconnect();
			resolve(undefined);
		};
		let timer = setTimeout(done, quietMs);
		const observer = new MutationObserver(() => {
			clearTimeout(timer);
			timer = setTimeout(done, quietMs);
		});
		observer.observe(document, {
			subtree: true,
			childList: true,
			attributes: true,
			characterData: true,
		});
		await promise;
	}, QUIET_MS);
}
