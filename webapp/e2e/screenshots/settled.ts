import { expect, type Page, type Request } from "@playwright/test";

/**
 * What the page shows while its data loads: a skeleton, or a region that says it is busy
 * (`webapp/DESIGN.md` § Loading and errors).
 */
const LOADING = '[data-slot="skeleton"], [aria-busy="true"]';

/** How long the document must stay unchanged to count as rendered. */
const QUIET_MS = 300;

/** How long one sample waits for the document to hold still before it reports that it does not. */
const SAMPLE_MS = 2000;

/** A read that ended this recently may not have rendered its answer yet. */
const RENDER_MS = 100;

/** The whole wait, below every test's own timeout, so that a failure names what still blocks. */
const SETTLE_MS = 45_000;

/**
 * The requests that carry the page's data: reads. Images, fonts and scripts have their own check, and
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
	const state: Watched = { open: new Set(), ended: Number.NEGATIVE_INFINITY };
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
 * Waits until the page shows its content. One sample at a time, each in one document: the document
 * holds still for `QUIET_MS`, then it shows no placeholder and its fonts and eager images are ready,
 * and no read is open or has just ended. The wait passes on the first sample with nothing that blocks,
 * and on timeout it names what the last sample found.
 *
 * Not `networkidle`: that needs 500 ms with no request at all, which overlapping polls can deny for
 * the whole test, and its timeout names no cause.
 */
export async function settled(page: Page): Promise<void> {
	watchRequests(page);
	const state = watched.get(page);
	if (state === undefined) {
		throw new Error("watchRequests did not record the page");
	}
	await expect
		.poll(async () => blockers(page, state), {
			message: "the page shows its content",
			timeout: SETTLE_MS,
			intervals: [100],
		})
		.toEqual([]);
}

/** What keeps the page from counting as ready now; empty when nothing does. */
async function blockers(page: Page, state: Watched): Promise<string[]> {
	const reads = () => [...state.open].map((request) => `open read ${request.url()}`);
	if (state.open.size > 0) {
		return reads();
	}
	let inPage: string[];
	try {
		inPage = await page.evaluate(sample, {
			quietMs: QUIET_MS,
			sampleMs: SAMPLE_MS,
			loading: LOADING,
		});
	} catch (error) {
		if (error instanceof Error && error.message.includes("Execution context was destroyed")) {
			return ["the page navigated"];
		}
		throw error;
	}
	const recent =
		performance.now() - state.ended < RENDER_MS
			? ["a read ended too recently to have rendered"]
			: [];
	return [...inPage, ...reads(), ...recent];
}

/**
 * Runs in the page. Waits until the document has not changed for `quietMs`, at most `sampleMs`, and
 * then reports what the document still lacks. Timers, not a clock: a screenshot test fixes the page's
 * clock, and its timers still run. A lazy image below the fold loads only when the page scrolls to it.
 */
async function sample({
	quietMs,
	sampleMs,
	loading,
}: {
	quietMs: number;
	sampleMs: number;
	loading: string;
}): Promise<string[]> {
	const { promise, resolve } = Promise.withResolvers<boolean>();
	let timer = setTimeout(() => resolve(true), quietMs);
	const limit = setTimeout(() => resolve(false), sampleMs);
	const observer = new MutationObserver(() => {
		clearTimeout(timer);
		timer = setTimeout(() => resolve(true), quietMs);
	});
	observer.observe(document, {
		subtree: true,
		childList: true,
		attributes: true,
		characterData: true,
	});
	const still = await promise;
	observer.disconnect();
	clearTimeout(timer);
	clearTimeout(limit);
	const found: string[] = [];
	if (!still) {
		found.push(`the document kept changing for ${sampleMs} ms`);
	}
	const placeholders = document.querySelectorAll(loading).length;
	if (placeholders > 0) {
		found.push(`${placeholders} loading placeholders`);
	}
	if (document.fonts.status !== "loaded") {
		found.push("fonts still loading");
	}
	for (const image of document.images) {
		if (!image.complete && image.loading !== "lazy") {
			found.push(`image still loading ${image.currentSrc || image.src}`);
		}
	}
	return found;
}
