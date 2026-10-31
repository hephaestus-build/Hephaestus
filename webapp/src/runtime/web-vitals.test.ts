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

/** A router whose first navigation resolves to the given matches when the test says so. */
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
	};
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
	const { router, resolve } = routerAt(...routeIds);
	const queryClient = new QueryClient();
	reportVisitVitals(router, queryClient);
	resolve();
	await vi.waitFor(() => expect(queryClient.isFetching()).toBe(0));
}

async function hide() {
	Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
	document.dispatchEvent(new Event("visibilitychange"));
	await sleep(0);
}

afterEach(() => {
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
	it("sends a signed-in visit's measurements once, when the reader first leaves", async () => {
		const sent = receive();
		await land(...PROFILE);
		reports.get("TTFB")?.({ name: "TTFB", value: 180, attribution: {} });
		reports.get("LCP")?.({ name: "LCP", value: 1250, attribution: { target: "PAGE" } });
		reports.get("CLS")?.({ name: "CLS", value: 0.01, attribution: { largestShiftTarget: "PAGE" } });
		reports.get("CLS")?.({
			name: "CLS",
			value: 0.04,
			attribution: { largestShiftTarget: "SIDEBAR" },
		});

		await hide();
		reports.get("CLS")?.({ name: "CLS", value: 0.09, attribution: { largestShiftTarget: "PAGE" } });
		await hide();

		await vi.waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]?.vitals).toStrictEqual([
			{ vital: "TTFB", page: "PRACTICE_PROFILE", region: "OTHER", value: 180 },
			{ vital: "LCP", page: "PRACTICE_PROFILE", region: "PAGE", value: 1250 },
			{ vital: "CLS", page: "PRACTICE_PROFILE", region: "SIDEBAR", value: 0.04 },
		]);
	});

	it("sends nothing more once the page comes back from the back-forward cache", async () => {
		const sent = receive();
		await land(...PROFILE);
		reports.get("LCP")?.({ name: "LCP", value: 1250, attribution: { target: "PAGE" } });
		await hide();
		await vi.waitFor(() => expect(sent).toHaveLength(1));

		dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
		reports.get("TTFB")?.({ name: "TTFB", value: 180, attribution: {} });
		reports.get("INP")?.({ name: "INP", value: 120, attribution: { interactionTarget: "HEADER" } });
		await hide();

		expect(sent).toHaveLength(1);
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
		reports.get("TTFB")?.({ name: "TTFB", value: 180, attribution: {} });
		await hide();

		await vi.waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]?.vitals).toStrictEqual([
			{ vital: "TTFB", page: "PRACTICE_PROFILE", region: "OTHER", value: 180 },
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
