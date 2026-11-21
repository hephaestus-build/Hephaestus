/**
 * Counts what a journey costs in the built app against the synthetic workspace of the user-guide
 * screenshots: the API reads it makes, the round trips they take, and the landmarks that layout shifts
 * move under the reader. `journeyCost` runs a journey twice, each time in a fresh browser context:
 *
 * - **In rounds.** Every API read waits until the page is quiet: nothing else in flight and its main
 *   thread idle. Then all waiting reads answer together, as one round. A read that the page starts
 *   only after another one answered lands in a later round, as it does over a real network, and the
 *   count does not depend on how fast the machine runs the page.
 * - **With a fixed latency.** Every API read answers after `LATENCY_MS`, as over a network, which is
 *   what layout shifts depend on: Chrome ignores a shift within 500 ms of the reader's input. A step
 *   is complete once it has answered exactly the reads that the first run made in that step.
 *
 * The live event stream is no read: it stays open.
 */
import { setTimeout } from "node:timers/promises";

import {
	expect,
	test,
	type Browser,
	type Page,
	type Request as PageRequest,
} from "@playwright/test";

import { getResponse } from "msw";
import type { WorkspaceMembership } from "@/api/types.gen";

import { asReturningReader } from "./screenshots/first-visit.ts";
import { useFixtureApi } from "./screenshots/fixture-api.ts";
import { handlers, NOW, WORKSPACE } from "./screenshots/workspace.ts";
import { E2E_BASE_URL } from "./urls.ts";

declare global {
	interface Window {
		/** Each layout shift's landmark, with the time Chrome gives the shift. */
		journeyShifts: { landmark: string; at: number }[];
		journeySettled: () => Promise<boolean>;
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

export const APP = new URL(E2E_BASE_URL).origin;
// On the app's own origin, as the server serves its API: the page's policy allows no other host to connect to.
export const API = `${APP}/api`;
const EVENTS = "/conversation/events";
const LATENCY_MS = 100;

export interface JourneyCost {
	/** Each API read, as `METHOD /path?query`, with the workspace path shortened to `/w`. */
	reads: string[];
	/**
	 * The rounds of reads that answered before the destination showed its content: the round trips the
	 * reader waited for. A round holds every read the page started while it waited for the one before.
	 */
	rounds: number;
	/**
	 * The landmarks that layout shifts moved without the reader's input. Chrome may report one shift as
	 * one entry or as several, depending on frame timing, so neither a count nor a score repeats. In a
	 * step that the reader starts, the shifts before its destination shows are the answer to their
	 * input and do not count: Chrome's own window for that is 500 ms, which a busy machine outlasts.
	 */
	moved: string[];
}

/**
 * Waits until `content`, text that only the step's destination has, shows. `byReader` says that the
 * reader's own input, such as a click, started the step.
 */
export type Step = (content: string, options?: { byReader?: boolean }) => Promise<void>;

type Answer = "in-rounds" | "after-latency";

/**
 * Runs `journey` in rounds, then with the fixed latency on `page`, and returns what its last step
 * cost: reads and rounds from the first run, moved landmarks from the second. Each `step` ends one
 * step of the journey; the last one is measured.
 */
export async function journeyCost(
	page: Page,
	role: WorkspaceMembership["role"],
	journey: (page: Page, step: Step) => Promise<void>,
): Promise<JourneyCost> {
	const { baseURL, viewport, userAgent, deviceScaleFactor, isMobile, hasTouch } =
		test.info().project.use;
	const inRounds = await freshPage(required(page.context().browser()), {
		baseURL,
		viewport,
		userAgent,
		deviceScaleFactor,
		isMobile,
		hasTouch,
	});
	const counted: JourneyCost[] = [];
	try {
		const instrumented = await instrument(inRounds, role, "in-rounds");
		await journey(inRounds, async (content, options) => {
			counted.push(await instrumented.settle(content, options?.byReader ?? false));
		});
	} finally {
		// A held read may still wait for its round.
		await inRounds.context().unrouteAll({ behavior: "ignoreErrors" });
		await inRounds.context().close();
	}
	const timed: JourneyCost[] = [];
	const instrumented = await instrument(page, role, "after-latency");
	await journey(page, async (content, options) => {
		timed.push(
			await instrumented.settle(
				content,
				options?.byReader ?? false,
				required(counted[timed.length]).reads,
			),
		);
	});
	const last = required(counted.at(-1));
	return { reads: last.reads, rounds: last.rounds, moved: required(timed.at(-1)).moved };
}

async function freshPage(
	browser: Browser,
	options: Parameters<Browser["newContext"]>[0],
): Promise<Page> {
	const context = await browser.newContext(options);
	return context.newPage();
}

function required<T>(value: T | null | undefined): T {
	if (value === null || value === undefined) {
		throw new Error("The journey ran a different number of steps, or no browser holds its page");
	}
	return value;
}

/** A page whose reads answer after the fixed latency, for a journey that only waits for its steps. */
export async function openJourney(page: Page, role: WorkspaceMembership["role"]) {
	return instrument(page, role, "after-latency");
}

async function instrument(page: Page, role: WorkspaceMembership["role"], answer: Answer) {
	const context = page.context();
	let reads: string[] = [];
	// In rounds, each read waiting for its round; with the fixed latency, the reads still out.
	const held = new Map<PageRequest, () => void>();
	let unanswered = 0;
	// Every request of the page in flight but the event stream: assets, held reads and answered ones.
	const inFlight = new Set<PageRequest>();
	const waiting = new Set<() => void>();
	// Resolves at the next request, answer, held read or close: what can end a wait below.
	const changed = async () =>
		// oxlint-disable-next-line promise/avoid-new -- Page events have no promise form that a set of waiters can share.
		new Promise<void>((resolve) => {
			waiting.add(resolve);
		});
	const changes = () => {
		for (const wake of waiting) {
			wake();
		}
		waiting.clear();
	};
	page.on("request", (request) => {
		if (!request.url().endsWith(EVENTS)) {
			inFlight.add(request);
			changes();
		}
	});
	const ended = (request: PageRequest) => {
		inFlight.delete(request);
		// A read the page aborted, as a query does that nothing needs any more, waits for no round.
		held.delete(request);
		changes();
	};
	page.on("requestfinished", ended);
	page.on("requestfailed", ended);
	page.on("close", changes);
	// Until the page has no short timer left and its event loop has gone idle (`journeySettled`). False
	// when a navigation replaced the document meanwhile: the new one is then waited for.
	const idle = async () =>
		page
			// A document from before the journey's script, such as the first blank one, has nothing to wait for.
			.evaluate(async () =>
				typeof window.journeySettled === "function" ? window.journeySettled() : true,
			)
			.catch(() => false);
	// Quiet: `drained` holds, and still holds once the page has settled.
	const quiet = async (drained: () => boolean) => {
		for (;;) {
			while (!page.isClosed() && !drained()) {
				await changed();
			}
			if (page.isClosed() || ((await idle()) && drained())) {
				return;
			}
		}
	};
	let rounds = 0;
	// The page's text before each round starts, with the rounds that answered before it.
	let before: { rounds: number; text: string }[] = [];
	if (answer === "in-rounds") {
		void (async () => {
			while (!page.isClosed()) {
				await quiet(() => inFlight.size === held.size);
				const text = await page.evaluate(() => document.body.textContent).catch(() => undefined);
				if (text === undefined) {
					// A navigation replaced the document after it was quiet, or the page closed.
					continue;
				}
				before.push({ rounds, text });
				if (held.size === 0) {
					await changed();
				} else {
					rounds += 1;
					const round = [...held.values()];
					held.clear();
					for (const release of round) {
						release();
					}
				}
			}
		})();
	}
	// A returning reader, who finished Get started, so no pill or card moves the page.
	await asReturningReader(context);
	await context.addInitScript(() => {
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
					window.journeyShifts.push(
						...shift.sources.map(({ node }) => ({ landmark: landmark(node), at: shift.startTime })),
					);
				}
			}
		}).observe({ type: "layout-shift", buffered: true });
	});
	// Nothing outside the app and its fixture API.
	await context.route(
		(url) => url.origin !== APP,
		async (route) => route.abort(),
	);
	await useFixtureApi(context, APP, API);
	await context.route(`${API}/**`, async (route) => {
		const request = route.request();
		const url = new URL(request.url());
		if (url.pathname.endsWith(EVENTS)) {
			await route.fulfill({ contentType: "text/event-stream", body: "retry: 3600000\n\n" });
			return;
		}
		reads.push(
			`${request.method()} ${url.pathname.slice("/api".length).replace(`/workspaces/${WORKSPACE}`, "/w")}${url.search}`,
		);
		// Together with the read: a listed read that is not yet unanswered would let a step end early.
		if (answer === "after-latency") {
			unanswered += 1;
		}
		const response = await getResponse(
			handlers(role),
			new Request(request.url(), { method: request.method(), headers: request.headers() }),
		);
		if (answer === "in-rounds") {
			// oxlint-disable-next-line promise/avoid-new -- The answer waits for its round, which no promise API names.
			await new Promise<void>((resolve) => {
				// A read the page aborted while its answer was being prepared waits for nothing.
				if (inFlight.has(request)) {
					held.set(request, resolve);
					changes();
				} else {
					resolve();
				}
			});
		} else {
			await setTimeout(LATENCY_MS);
		}
		try {
			await (response === undefined
				? route.fulfill({ status: 404 })
				: route.fulfill({
						status: response.status,
						headers: Object.fromEntries(response.headers),
						body: Buffer.from(await response.arrayBuffer()),
					}));
		} catch (error) {
			// A read the page aborted while it waited for its answer has nobody to answer.
			if (inFlight.has(request)) {
				throw error;
			}
		} finally {
			if (answer === "after-latency") {
				unanswered -= 1;
				changes();
			}
		}
	});
	await page.clock.setFixedTime(NOW);
	// After the clock, so it wraps the clock's timers rather than the clock wrapping it: the clock's own
	// timers are no work of the page's.
	await context.addInitScript(() => {
		// A timer of up to 400 ms is work the page has scheduled, such as React revealing a lazily loaded
		// part after 300 ms, which can start a read. A longer one is a retry or a refresh, such as the live
		// stream's reconnect after at least 500 ms, which no journey waits for.
		const native = { set: window.setTimeout.bind(window), clear: window.clearTimeout.bind(window) };
		const short = new Set<number>();
		// Each short timer's companion, set for the same delay after it, so it fires once the timer ran.
		const companions = new Map<number, number>();
		const settled: (() => void)[] = [];
		const forget = (id: number) => {
			short.delete(id);
			companions.delete(id);
			if (short.size === 0) {
				for (const resolve of settled.splice(0)) {
					resolve();
				}
			}
		};
		const set = (handler: TimerHandler, delay?: number, ...rest: unknown[]): number => {
			const id = native.set(handler, delay, ...rest);
			if ((delay ?? 0) <= 400) {
				short.add(id);
				companions.set(
					id,
					native.set(() => {
						forget(id);
					}, delay),
				);
			}
			return id;
		};
		const clear = (id?: number): void => {
			if (id !== undefined) {
				const companion = companions.get(id);
				if (companion !== undefined) {
					native.clear(companion);
				}
				forget(id);
			}
			native.clear(id);
		};
		Object.defineProperty(window, "setTimeout", { value: set, configurable: true, writable: true });
		Object.defineProperty(window, "clearTimeout", {
			value: clear,
			configurable: true,
			writable: true,
		});
		// No short timer left, then an idle period, twice: what the page starts after one may need the other.
		window.journeySettled = async () => {
			for (const turn of [1, 2]) {
				if (short.size > 0) {
					// oxlint-disable-next-line promise/avoid-new -- A set of timers has no promise form.
					await new Promise<void>((resolve) => {
						settled.push(resolve);
					});
				}
				// oxlint-disable-next-line promise/avoid-new -- `requestIdleCallback` has no promise form.
				await new Promise<number>((resolve) => {
					requestIdleCallback(() => {
						resolve(turn);
					});
				});
			}
			return short.size === 0;
		};
	});
	let base = 0;
	return {
		/**
		 * What the step cost, once the page shows `content`, and then forgets it, so the next step is
		 * measured alone. In rounds, the rounds are those that answered before the first quiet moment that
		 * showed the content, and the step ends once the page is quiet with no read waiting. With the
		 * fixed latency, it ends once it has answered `expected`, the reads the step made in rounds, and
		 * the page is quiet. Two frames then let the last layout shifts be reported. In a step
		 * `byReader`, the shifts from before the frame that showed the content do not count.
		 */
		settle: async (
			content: string,
			byReader = false,
			expected?: string[],
		): Promise<JourneyCost> => {
			// Chrome delivers a shift to the observer after its frame, so the shifts before the content
			// showed are told apart by their time, not by when they arrived.
			const showing = await page.waitForFunction(
				({ text, reader }) =>
					document.body.textContent.includes(text) && { since: reader ? performance.now() : 0 },
				{ text: content, reader: byReader },
				{ polling: "raf" },
			);
			// `waitForFunction` resolves only on a truthy value, which its type does not express.
			const value = await showing.jsonValue();
			const since = value === false ? 0 : value.since;
			await expect(page.getByText(content).first()).toBeVisible();
			let shown = rounds;
			if (answer === "in-rounds") {
				await quiet(() => inFlight.size === 0 && held.size === 0);
				shown = before.find(({ text }) => text.includes(content))?.rounds ?? rounds;
			} else if (expected === undefined) {
				await quiet(() => unanswered === 0);
			} else {
				await expect
					.poll(() => (unanswered === 0 ? reads.toSorted() : undefined), {
						message: "The step answers the reads that it made in rounds",
					})
					.toStrictEqual(expected);
				// The last answer reaches the page and renders after the route fulfils it.
				await quiet(() => unanswered === 0);
			}
			const moved = await page.evaluate(async (after) => {
				for (const frame of [1, 2]) {
					// oxlint-disable-next-line promise/avoid-new -- `requestAnimationFrame` has no promise form.
					await new Promise<number>((resolve) => {
						requestAnimationFrame(() => {
							resolve(frame);
						});
					});
				}
				const shifts = window.journeyShifts;
				window.journeyShifts = [];
				return shifts.filter(({ at }) => at >= after).map(({ landmark }) => landmark);
			}, since);
			const cost = {
				reads: reads.toSorted(),
				rounds: shown - base,
				moved: [...new Set(moved)].toSorted(),
			};
			reads = [];
			before = [];
			base = rounds;
			return cost;
		},
	};
}

export { WORKSPACE } from "./screenshots/workspace.ts";
