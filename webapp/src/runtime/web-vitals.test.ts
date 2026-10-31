import { noop, QueryClient } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { WebVitalsReport } from "@/api/types.gen";
import { zWebVitalsReport } from "@/api/zod.gen";
import { server } from "@/mocks/server";
import { mockFeatureFlags } from "@/runtime/feature-flags/testing";
import { USER_VIEW_STORAGE_KEY } from "@/runtime/user-view/session";
import { sleep } from "@/test/async";

import { journeyPageOf, landmarkOf, reportVisitVitals } from "./web-vitals";

type Report = (metric: unknown) => void;
const reports = vi.hoisted(() => new Map<string, Report>());
vi.mock("web-vitals/attribution", () => ({
	onLCP: (report: Report) => reports.set("LCP", report),
	onINP: (report: Report) => reports.set("INP", report),
	onCLS: (report: Report) => reports.set("CLS", report),
	onTTFB: (report: Report) => reports.set("TTFB", report),
}));

/** A router whose navigations resolve to the given matches when the test says so. */
function routerAt(...routeIds: string[]) {
	let resolved: () => void = noop;
	const router = {
		state: { matches: routeIds.map((routeId) => ({ routeId })) },
		subscribe: (_event: string, listener: () => void) => {
			resolved = listener;
			return noop;
		},
	};
	return {
		// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- A stand-in for the two router members the reporter reads.
		router: router as unknown as AnyRouter,
		resolve: () => resolved(),
		/** Moves to `path`, as a route change does, and resolves its matches. */
		navigate: (path: string, ...next: string[]) => {
			history.pushState(null, "", path);
			router.state.matches = next.map((routeId) => ({ routeId }));
			resolved();
		},
	};
}

/** A measurement as `web-vitals` reports it: one metric instance, named by its id. */
function measured(
	name: string,
	value: number,
	{
		id = `${name}-load`,
		attribution = {},
		navigationType = "navigate",
		navigationURL = location.href,
	}: { id?: string; attribution?: object; navigationType?: string; navigationURL?: string } = {},
) {
	return { name, value, id, attribution, navigationType, navigationURL };
}

const USER_VIEW = {
	operatorAccountId: 1,
	workspaceSlug: "acme",
	workspaceName: "Acme",
	accountId: "2",
	providerLogin: "ada",
	name: "Ada",
	unclaimed: false,
	reason: "Support request",
};

const PROFILE = [
	"__root__",
	"/_authenticated",
	"/_authenticated/w/$workspaceSlug/practice-profile",
];

/** Answers the reader's flags with the operator's switch, and collects the reports the server receives. */
function receive({ switchedOn = true } = {}) {
	const sent: WebVitalsReport[] = [];
	server.use(
		http.get("*/user/features", () =>
			HttpResponse.json(mockFeatureFlags({ PAGE_EXPERIENCE_METRICS: switchedOn })),
		),
		http.post("*/web-vitals", async ({ request }) => {
			sent.push(zWebVitalsReport.parse(await request.json()));
			return new HttpResponse(null, { status: 204 });
		}),
	);
	return sent;
}

/** Starts the reporter, as `main.tsx` does, and resolves the landing page. */
async function land(...routeIds: string[]) {
	const stub = routerAt(...routeIds);
	const queryClient = new QueryClient();
	reportVisitVitals(stub.router, queryClient);
	stub.resolve();
	await vi.waitFor(() => expect(queryClient.isFetching()).toBe(0));
	return stub;
}

async function hide() {
	Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
	document.dispatchEvent(new Event("visibilitychange"));
	await sleep(0);
}

afterEach(() => {
	history.replaceState(null, "", "/");
	reports.clear();
	sessionStorage.clear();
	Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
});

describe("journeyPageOf", () => {
	it("names a journey page and folds every other page into OTHER", () => {
		expect(
			journeyPageOf([
				"__root__",
				"/_authenticated",
				"/_authenticated/w/$workspaceSlug/conversation",
			]),
		).toBe("ASK_HEPHAESTUS");
		expect(journeyPageOf(["__root__", "/_authenticated", "/_authenticated/settings"])).toBe(
			"OTHER",
		);
	});
});

/** A new element inside a new `parent` element on the page. */
function placeIn(parent: string) {
	const container = document.createElement(parent);
	const child = document.createElement("span");
	container.append(child);
	document.body.append(container);
	return child;
}

describe("landmarkOf", () => {
	it("names the landmark an element sits in", () => {
		const sidebar = document.createElement("div");
		sidebar.dataset.slot = "sidebar";
		const link = document.createElement("a");
		sidebar.append(link);
		document.body.append(sidebar);
		const text = document.createTextNode("Text");
		placeIn("main").append(text);

		expect(landmarkOf(link)).toBe("SIDEBAR");
		expect(landmarkOf(placeIn("header"))).toBe("HEADER");
		expect(landmarkOf(text)).toBe("PAGE");
		expect(landmarkOf(placeIn("footer"))).toBe("OTHER");
		expect(landmarkOf(null)).toBe("OTHER");
	});
});

describe("reportVisitVitals", () => {
	it("sends each measurement once, with the first value that web-vitals reports for it", async () => {
		const sent = receive();
		await land(...PROFILE);
		reports.get("TTFB")?.(measured("TTFB", 180));
		reports.get("LCP")?.(measured("LCP", 1250, { attribution: { target: "PAGE" } }));
		reports.get("CLS")?.(measured("CLS", 0.04, { attribution: { largestShiftTarget: "SIDEBAR" } }));

		await hide();
		reports.get("CLS")?.(measured("CLS", 0.09, { attribution: { largestShiftTarget: "PAGE" } }));
		await hide();

		await vi.waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]?.vitals).toStrictEqual([
			{ vital: "TTFB", navigation: "LOAD", page: "PRACTICE_PROFILE", region: "OTHER", value: 180 },
			{ vital: "LCP", navigation: "LOAD", page: "PRACTICE_PROFILE", region: "PAGE", value: 1250 },
			{
				vital: "CLS",
				navigation: "LOAD",
				page: "PRACTICE_PROFILE",
				region: "SIDEBAR",
				value: 0.04,
			},
		]);
	});

	it("names a route view by the page it showed, without a time to first byte, and sends it at the next hide", async () => {
		const sent = receive();
		const { navigate } = await land(...PROFILE);
		reports.get("LCP")?.(measured("LCP", 1250, { attribution: { target: "PAGE" } }));
		await hide();
		await vi.waitFor(() => expect(sent).toHaveLength(1));

		Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
		navigate(
			"/w/acme/activity#latest",
			"__root__",
			"/_authenticated",
			"/_authenticated/w/$workspaceSlug/activity",
		);
		const view = {
			navigationType: "soft-navigation",
			navigationURL: `${location.origin}/w/acme/activity`,
		};
		reports.get("TTFB")?.(measured("TTFB", 0, { ...view, id: "TTFB-view" }));
		reports.get("INP")?.(
			measured("INP", 320, {
				...view,
				id: "INP-view",
				attribution: { interactionTarget: "HEADER" },
			}),
		);
		reports.get("INP")?.(
			measured("INP", 90, {
				navigationType: "soft-navigation",
				navigationURL: `${location.origin}/w/acme/gone`,
				id: "INP-gone",
			}),
		);
		await hide();

		await vi.waitFor(() => expect(sent).toHaveLength(2));
		expect(sent[1]?.vitals).toStrictEqual([
			{ vital: "INP", navigation: "ROUTE", page: "ACTIVITY", region: "HEADER", value: 320 },
			{ vital: "INP", navigation: "ROUTE", page: "OTHER", region: "OTHER", value: 90 },
		]);
	});

	it("sends nothing more once the page comes back from the back-forward cache", async () => {
		const sent = receive();
		await land(...PROFILE);
		reports.get("LCP")?.(measured("LCP", 1250, { attribution: { target: "PAGE" } }));
		await hide();
		await vi.waitFor(() => expect(sent).toHaveLength(1));

		// `web-vitals` reports the restored page from a capture-phase listener, during the dispatch.
		const restoredTiming = () =>
			reports.get("TTFB")?.(
				measured("TTFB", 0, { id: "TTFB-restored", navigationType: "back-forward-cache" }),
			);
		addEventListener("pageshow", restoredTiming, { capture: true });
		dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
		removeEventListener("pageshow", restoredTiming, { capture: true });
		reports.get("INP")?.(
			measured("INP", 120, { id: "INP-restored", attribution: { interactionTarget: "HEADER" } }),
		);
		await hide();

		expect(sent).toHaveLength(1);
	});

	it("sends a full batch at once, so a long visit loses no measurement", async () => {
		const sent = receive();
		await land(...PROFILE);
		for (const view of Array.from({ length: 45 }, (_, index) => index)) {
			reports.get("INP")?.(
				measured("INP", 100 + view, { id: `INP-${view}`, navigationType: "soft-navigation" }),
			);
		}
		await hide();

		await vi.waitFor(() => expect(sent).toHaveLength(2));
		expect(sent.map(({ vitals }) => vitals.length)).toStrictEqual([40, 5]);
		expect(sent.flatMap(({ vitals }) => vitals.map(({ value }) => value))).toStrictEqual(
			Array.from({ length: 45 }, (_, view) => 100 + view),
		);
	});

	it("measures nothing and sends nothing until the operator turns page experience metrics on", async () => {
		const sent = receive({ switchedOn: false });
		await land(...PROFILE);

		await hide();

		expect(reports.size).toBe(0);
		expect(sent).toStrictEqual([]);
	});

	it("measures nothing for a visit that started outside sign-in or in a user view", async () => {
		const sent = receive();
		await land("__root__", "/login");
		sessionStorage.setItem(USER_VIEW_STORAGE_KEY, JSON.stringify(USER_VIEW));
		await land(...PROFILE);

		await hide();

		expect(reports.size).toBe(0);
		expect(sent).toStrictEqual([]);
	});

	it("sends a visit the reader left before it could measure the next time they leave", async () => {
		const sent = receive();
		const { router, resolve } = routerAt(...PROFILE);
		reportVisitVitals(router, new QueryClient());
		await hide();
		resolve();
		await vi.waitFor(() => expect(reports.size).toBe(4));
		reports.get("TTFB")?.(measured("TTFB", 180));
		await hide();

		await vi.waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]?.vitals).toStrictEqual([
			{ vital: "TTFB", navigation: "LOAD", page: "PRACTICE_PROFILE", region: "OTHER", value: 180 },
		]);
	});

	it("measures nothing for a page that came back from the back-forward cache before it loaded", async () => {
		const sent = receive();
		const { router, resolve } = routerAt(...PROFILE);
		const queryClient = new QueryClient();
		reportVisitVitals(router, queryClient);
		await hide();
		dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
		resolve();
		await vi.waitFor(() => expect(queryClient.isFetching()).toBe(0));
		await hide();

		expect(reports.size).toBe(0);
		expect(sent).toStrictEqual([]);
	});
});
