import { expect, type Page, type Request } from "@playwright/test";

/**
 * What the page shows while its data loads: a skeleton, or a region that says it is busy
 * (`webapp/DESIGN.md` § Loading and errors).
 */
const LOADING = '[data-slot="skeleton"], [aria-busy="true"]';

/** The requests that carry the page's data. Images, fonts and scripts have their own waits. */
const DATA = new Set(["fetch", "xhr"]);

/** How long the document must stay unchanged to count as rendered. */
const QUIET_MS = 300;

interface Watched {
	open: Set<Request>;
	started: number;
}

const watched = new WeakMap<Page, Watched>();

/**
 * Records the page's data requests from now on. Call it before the page navigates: `settled` waits
 * only for the requests it has seen.
 */
export function watchRequests(page: Page): void {
	if (watched.has(page)) {
		return;
	}
	const state: Watched = { open: new Set(), started: 0 };
	watched.set(page, state);
	page.on("request", (request) => {
		if (DATA.has(request.resourceType())) {
			state.open.add(request);
			state.started += 1;
		}
	});
	const end = (request: Request) => state.open.delete(request);
	page.on("requestfinished", end);
	page.on("requestfailed", end);
}

/**
 * Waits until the page shows its content: nothing still loads, no data request is open, every eager
 * image and every font is ready, and the document has not changed for a moment without a new request.
 * The test's own timeout bounds each wait.
 *
 * Not `networkidle`: that waits for 500 ms with no request at all, which a poll can deny for the whole
 * test, and then the timeout names no cause. Here a request that never ends fails the wait with its
 * address. A query that shows no loading state is still waited for, through its request.
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
				message: "the page's data requests end",
				timeout: 0,
			})
			.toEqual([]);
		const { started } = state;
		// A page that redirects replaces its document while it is read: wait again on the new one.
		if (!(await rendered(page))) {
			continue;
		}
		// An answer can start the next request; its content is not on the page yet.
		if (state.started === started && state.open.size === 0) {
			return;
		}
	}
}

/**
 * Every eager image and every font is ready, and then the document has not changed for `QUIET_MS`.
 * False when the page navigated meanwhile.
 */
async function rendered(page: Page): Promise<boolean> {
	try {
		// A lazy image below the fold loads only when the page scrolls to it.
		await page.waitForFunction(
			async () => {
				await document.fonts.ready;
				return [...document.images].every((image) => image.complete || image.loading === "lazy");
			},
			undefined,
			{ timeout: 0 },
		);
		await quiet(page);
		return true;
	} catch (error) {
		if (error instanceof Error && error.message.includes("Execution context was destroyed")) {
			return false;
		}
		throw error;
	}
}

/**
 * The document has not changed for `QUIET_MS`. Timers, not a clock: a screenshot test fixes the page's
 * clock, and its timers still run.
 */
async function quiet(page: Page): Promise<void> {
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
