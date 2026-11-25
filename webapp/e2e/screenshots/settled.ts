import { expect, type Page } from "@playwright/test";

/**
 * What the page shows while something loads: a skeleton, a Suspense fallback built from one, or a
 * region that says it is busy (`webapp/DESIGN.md` § Loading and errors).
 */
const LOADING = '[data-slot="skeleton"], [aria-busy="true"]';

/**
 * How far apart two reads must be that see the same idle period. A read that starts between them,
 * whose notification the query cache delivers a task later, ends that period first.
 */
const CONFIRM_MS = 150;

/** The whole wait, below every test's own timeout, so that a failure names what still blocks. */
const SETTLE_MS = 45_000;

interface Sample {
	/**
	 * The document and its idle period (`installPageIdle` in `src/runtime/page-idle.ts`), or undefined
	 * while it loads. Two reads that match saw the same document stay idle.
	 */
	period: string | undefined;
	blockers: string[];
}

/**
 * Waits until the page shows its content: two reads at least `CONFIRM_MS` apart see the same idle
 * period of the app, no loading placeholder, and fonts and eager images ready for the scan. A lazy
 * image below the fold loads only when the page scrolls to it. On timeout it names what the last
 * read found.
 *
 * Not `networkidle`: that needs 500 ms with no request at all, which a poll can deny for the whole
 * test, and its timeout names no cause. The app knows when it has loaded; the network does not.
 */
export async function settled(page: Page): Promise<void> {
	let confirmed: { period: string; at: number } | undefined;
	await expect
		.poll(
			async () => {
				const { period, blockers } = await sample(page);
				if (period === undefined) {
					confirmed = undefined;
					return ["the app is still loading", ...blockers];
				}
				if (blockers.length > 0) {
					confirmed = undefined;
					return blockers;
				}
				const now = performance.now();
				if (confirmed?.period !== period) {
					confirmed = { period, at: now };
				}
				return now - confirmed.at >= CONFIRM_MS ? [] : ["the idle period is not confirmed yet"];
			},
			{ message: "the page shows its content", timeout: SETTLE_MS, intervals: [CONFIRM_MS] },
		)
		.toEqual([]);
}

async function sample(page: Page): Promise<Sample> {
	try {
		return await page.evaluate((loading): Sample => {
			const blockers: string[] = [];
			const placeholders = document.querySelectorAll(loading).length;
			if (placeholders > 0) {
				blockers.push(`${placeholders} loading placeholders`);
			}
			if (document.fonts.status !== "loaded") {
				blockers.push("fonts are still loading");
			}
			for (const image of document.images) {
				if (!image.complete && image.loading !== "lazy") {
					blockers.push(`image still loading: ${image.currentSrc || image.src}`);
				}
			}
			return { period: document.documentElement.dataset.pageIdle, blockers };
		}, LOADING);
	} catch (error) {
		// A page that redirects replaces its document while it is read.
		if (error instanceof Error && error.message.includes("Execution context was destroyed")) {
			return { period: undefined, blockers: ["the page navigated"] };
		}
		throw error;
	}
}
