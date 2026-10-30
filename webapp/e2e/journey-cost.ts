/**
 * Counts what a journey costs in the built app against the synthetic workspace of the user-guide
 * screenshots: the API reads it makes, the round trips they take, and the landmarks that layout shifts
 * move under the reader. Every API answer waits `LATENCY_MS`, so a read that starts only after another
 * one answered lands in a later round, as it does over a real network. The order of the browser's
 * request timings, as Playwright reports them, stands in for the dependency between reads; it does not
 * prove one. The live event
 * stream is no read: it stays open, and when it opens depends on timing.
 */
import { setTimeout } from "node:timers/promises";

import { expect, type Page } from "@playwright/test";

import { getResponse } from "msw";
import type { WorkspaceMembership } from "@/api/types.gen";

import { handlers, NOW, WORKSPACE } from "./screenshots/workspace.ts";
import { E2E_BASE_URL } from "./urls.ts";

declare global {
	interface Window {
		journeyShifts: string[];
	}
	/** Chromium's layout-shift entry, which TypeScript's DOM types do not describe yet. */
	interface LayoutShift extends PerformanceEntry {
		hadRecentInput: boolean;
		sources: { node: Node | null }[];
	}
	interface PerformanceObserverEntryList {
		// oxlint-disable-next-line typescript/method-signature-style -- Only a method signature merges as an overload of the DOM's own.
		getEntriesByType(type: "layout-shift"): LayoutShift[];
	}
}

const API = "http://api.test";
const APP = new URL(E2E_BASE_URL).origin;
const EVENTS = "/mentor/events";
const LATENCY_MS = 100;

export interface JourneyCost {
	/** Each API read, as `METHOD /path?query`, with the workspace path shortened to `/w`. */
	reads: string[];
	/**
	 * The longest chain of reads where each started only after an earlier one had answered, among the
	 * reads that answered before the destination showed its content: the round trips the reader waited
	 * for. A read that a lazily loaded part starts later, such as the conversation panel's, is no part
	 * of it, and when such a part loads depends on timing.
	 */
	rounds: number;
	/**
	 * The landmarks that layout shifts moved without the reader's input. Chrome may report one shift as
	 * one entry or as several, depending on frame timing, so neither a count nor a score repeats.
	 */
	moved: string[];
}

interface Timing {
	start: number;
	end: number;
}

/** The longest chain of reads where each one started only after the one before it had answered. */
export function longestChain(timings: Timing[]): number {
	const byStart = timings.toSorted((a, b) => a.start - b.start);
	const rounds: number[] = [];
	for (const [index, read] of byStart.entries()) {
		const before = byStart.flatMap((earlier, at) =>
			earlier.end <= read.start ? [rounds[at] ?? 0] : [],
		);
		rounds[index] = 1 + Math.max(0, ...before);
	}
	return Math.max(0, ...rounds);
}

export async function openJourney(page: Page, role: WorkspaceMembership["role"]) {
	const context = page.context();
	const reads: string[] = [];
	const timings: Timing[] = [];
	page.on("requestfinished", (request) => {
		const url = new URL(request.url());
		if (url.origin === API && !url.pathname.endsWith(EVENTS)) {
			const { startTime, responseEnd } = request.timing();
			timings.push({ start: startTime, end: startTime + responseEnd });
		}
	});
	await context.addInitScript(() => {
		// A returning reader has closed the practice profile's reading guide.
		localStorage.setItem("hephaestus:section-folded:practice-profile:how-to-read", "true");
		window.journeyShifts = [];
		// oxlint-disable-next-line unicorn/consistent-function-scoping -- `addInitScript` sends this function's source to the page, so its helpers must be inside it.
		const landmark = (node: Node | null) => {
			const element = node instanceof Element ? node : node?.parentElement;
			if (element?.closest('[data-slot="sidebar"]')) {
				return "sidebar";
			}
			if (element?.closest("header")) {
				return "header";
			}
			return element?.closest("main") ? "page" : "other";
		};
		new PerformanceObserver((list) => {
			for (const shift of list.getEntriesByType("layout-shift")) {
				if (!shift.hadRecentInput) {
					window.journeyShifts.push(...shift.sources.map(({ node }) => landmark(node)));
				}
			}
		}).observe({ type: "layout-shift", buffered: true });
	});
	// Nothing outside the app and its fixture API.
	await context.route(
		(url) => url.origin !== API && url.origin !== APP,
		async (route) => route.abort(),
	);
	await context.route("**/env-config.js", async (route) =>
		route.fulfill({
			contentType: "application/javascript",
			body: `window.__ENV__ = ${JSON.stringify({ APPLICATION_SERVER_URL: API, APPLICATION_CLIENT_URL: APP })};`,
		}),
	);
	await context.route(`${API}/**`, async (route) => {
		const request = route.request();
		const url = new URL(request.url());
		if (url.pathname.endsWith(EVENTS)) {
			return route.fulfill({ contentType: "text/event-stream", body: "retry: 3600000\n\n" });
		}
		reads.push(
			`${request.method()} ${url.pathname.replace(`/workspaces/${WORKSPACE}`, "/w")}${url.search}`,
		);
		const response = await getResponse(
			handlers(role),
			new Request(request.url(), { method: request.method(), headers: request.headers() }),
		);
		await setTimeout(LATENCY_MS);
		if (response === undefined) {
			return route.fulfill({ status: 404 });
		}
		return route.fulfill({
			status: response.status,
			headers: Object.fromEntries(response.headers),
			body: Buffer.from(await response.arrayBuffer()),
		});
	});
	await page.clock.setFixedTime(NOW);
	return {
		/** Forgets what earlier steps cost, so the next step is measured alone. */
		reset: async () => {
			reads.length = 0;
			timings.length = 0;
			await page.evaluate(() => {
				window.journeyShifts = [];
			});
		},
		/**
		 * What the journey cost, once the page shows `content`, text that only the destination has. The
		 * page checks for it every frame, so the moment it shows is known to a frame; a locator's visibility
		 * check polls up to 500 ms apart, long enough for a later read to answer. The quiet network then
		 * drains the reads the page started, and two frames let the last layout shifts be reported.
		 */
		settle: async (content: string): Promise<JourneyCost> => {
			await page.waitForFunction((text) => document.body.textContent.includes(text), content, {
				polling: "raf",
			});
			// Playwright reports request timings on the same wall clock.
			const shownAt = Date.now();
			await expect(page.getByText(content).first()).toBeVisible();
			await page.waitForLoadState("networkidle");
			const moved = await page.evaluate(async () => {
				for (const frame of [1, 2]) {
					// oxlint-disable-next-line promise/avoid-new -- `requestAnimationFrame` has no promise form.
					await new Promise<number>((resolve) => {
						requestAnimationFrame(() => {
							resolve(frame);
						});
					});
				}
				return window.journeyShifts;
			});
			return {
				reads: reads.toSorted(),
				rounds: longestChain(timings.filter(({ end }) => end <= shownAt)),
				moved: [...new Set(moved)].toSorted(),
			};
		},
	};
}

export { WORKSPACE } from "./screenshots/workspace.ts";
