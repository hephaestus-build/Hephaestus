import { expect, type Page, type Request } from "@playwright/test";

/**
 * What the page shows while its data loads: a skeleton, or a region that says it is busy
 * (`webapp/DESIGN.md` § Loading and errors).
 */
const LOADING = '[data-slot="skeleton"], [aria-busy="true"]';

/**
 * How long the page must stay still: no document change and no read ending. The app's shortest poll is
 * 2 s (`DELIVERY_POLL_MS`), so every page has such a moment between its reads.
 */
const QUIET_MS = 300;

/**
 * The requests that carry the page's data: reads. Images, fonts and scripts have their own wait, and
 * a write, such as a page-experience beacon the last document sent on its way out, is not data to show.
 */
function isData(request: Request): boolean {
	return (
		(request.resourceType() === "fetch" || request.resourceType() === "xhr") &&
		request.method() === "GET"
	);
}

interface Watched {
	open: Set<Request>;
	/** When the last read ended, on this process's clock. */
	ended: number;
}

const watched = new WeakMap<Page, Watched>();

/**
 * Records the page's reads from now on. Call it before the page navigates: `settled` waits only for
 * the reads it has seen.
 */
export function watchRequests(page: Page): void {
	if (watched.has(page)) {
		return;
	}
	const state: Watched = { open: new Set(), ended: 0 };
	watched.set(page, state);
	page.on("request", (request) => {
		if (isData(request)) {
			state.open.add(request);
		}
	});
	const end = (request: Request) => {
		if (state.open.delete(request)) {
			state.ended = performance.now();
		}
	};
	page.on("requestfinished", end);
	page.on("requestfailed", end);
}

/**
 * Waits until the page shows its content: no placeholder, no read open and none ended in the last
 * `QUIET_MS`, a document that has not changed for `QUIET_MS`, and its fonts and eager images ready.
 * All of it holds at one moment, in the document that is current then. The test's own timeout bounds
 * the wait.
 *
 * Not `networkidle`: that waits for 500 ms with no request at all, and its timeout names no cause.
 * Here a read that never ends fails the wait with its address, and a query that shows no loading
 * state is still waited for, through its read.
 */
export async function settled(page: Page): Promise<void> {
	watchRequests(page);
	const state = watched.get(page);
	if (state === undefined) {
		throw new Error("watchRequests did not record the page");
	}
	for (;;) {
		await expect(page.locator(LOADING), "the page loads its data").toHaveCount(0, { timeout: 0 });
		await expect
			.poll(() => [...state.open].map((request) => `${request.method()} ${request.url()}`), {
				message: "the page's reads end",
				timeout: 0,
			})
			.toEqual([]);
		if (!(await still(page))) {
			continue;
		}
		// Checked after the page held still: a placeholder that a redirect brought, or a read that
		// started or ended meanwhile, starts the wait again.
		const placeholders = await page.locator(LOADING).count();
		if (
			placeholders === 0 &&
			state.open.size === 0 &&
			performance.now() - state.ended >= QUIET_MS
		) {
			return;
		}
	}
}

/**
 * The document stays unchanged for `QUIET_MS`, and then its fonts and eager images are ready, all in
 * one call in one document. False when they are not, or when the page navigated meanwhile. Timers,
 * not a clock: a screenshot test fixes the page's clock, and its timers still run. A lazy image below
 * the fold loads only when the page scrolls to it.
 */
async function still(page: Page): Promise<boolean> {
	try {
		return await page.evaluate(async (quietMs) => {
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
			return (
				document.fonts.status === "loaded" &&
				[...document.images].every((image) => image.complete || image.loading === "lazy")
			);
		}, QUIET_MS);
	} catch (error) {
		if (error instanceof Error && error.message.includes("Execution context was destroyed")) {
			return false;
		}
		throw error;
	}
}
