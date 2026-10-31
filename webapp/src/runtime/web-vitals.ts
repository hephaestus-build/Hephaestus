import { noop, type QueryClient } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";
import type {
	CLSMetricWithAttribution,
	INPMetricWithAttribution,
	LCPMetricWithAttribution,
	TTFBMetricWithAttribution,
} from "web-vitals/attribution";

import { reportWebVitals } from "@/api/sdk.gen";
import type { WebVital } from "@/api/types.gen";
import type { FileRoutesById } from "@/routeTree.gen";
import { featureFlagsQueryOptions } from "@/runtime/feature-flags/hooks";
import { getUserViewSession } from "@/runtime/user-view/session";

/** The journey pages the server knows by name. Every other page reports as `OTHER`. */
const JOURNEY_PAGES: [keyof FileRoutesById, WebVital["page"]][] = [
	["/_authenticated/w/$workspaceSlug/practice-profile", "PRACTICE_PROFILE"],
	["/_authenticated/w/$workspaceSlug/conversation", "ASK_HEPHAESTUS"],
	["/_authenticated/w/$workspaceSlug/feedback/$itemId", "FEEDBACK"],
	["/_authenticated/w/$workspaceSlug/feedback_/$itemId/$artifactId", "FEEDBACK"],
	["/_authenticated/w/$workspaceSlug/admin/practices/reviews/feedback", "FEEDBACK_APPROVAL"],
	["/_authenticated/w/$workspaceSlug/activity", "ACTIVITY"],
];

const SIGNED_IN: keyof FileRoutesById = "/_authenticated";

/** The journey page of a matched route, from the route ids of its matches. */
export function journeyPageOf(routeIds: readonly string[]): WebVital["page"] {
	return JOURNEY_PAGES.find(([routeId]) => routeIds.includes(routeId))?.[1] ?? "OTHER";
}

/** The landmark an element sits in, which names where on the page a measurement happened. */
export function landmarkOf(node: Node | null): WebVital["region"] {
	const element = node instanceof Element ? node : node?.parentElement;
	if (element?.closest('[data-slot="sidebar"]')) {
		return "SIDEBAR";
	}
	if (element?.closest("header")) {
		return "HEADER";
	}
	return element?.closest("main") ? "PAGE" : "OTHER";
}

const REGIONS = new Set<string>([
	"SIDEBAR",
	"HEADER",
	"PAGE",
	"OTHER",
] satisfies WebVital["region"][]);
const isRegion = (target: string | undefined): target is WebVital["region"] =>
	target !== undefined && REGIONS.has(target);

type Measured =
	| LCPMetricWithAttribution
	| INPMetricWithAttribution
	| CLSMetricWithAttribution
	| TTFBMetricWithAttribution;

/** The region `web-vitals` attributed a measurement to, through `landmarkOf` as its target. */
function regionOf(metric: Measured): WebVital["region"] {
	switch (metric.name) {
		case "LCP": {
			return isRegion(metric.attribution.target) ? metric.attribution.target : "OTHER";
		}
		case "INP": {
			const target = metric.attribution.interactionTarget;
			return isRegion(target) ? target : "OTHER";
		}
		case "CLS": {
			const target = metric.attribution.largestShiftTarget;
			return isRegion(target) ? target : "OTHER";
		}
		case "TTFB": {
			return "OTHER";
		}
	}
}

/** What a page visit measured so far: the latest value of each vital, with its region. */
type Measurements = Map<Measured["name"], Omit<WebVital, "page">>;

/**
 * Measures a visit and sends what it measured once, when the reader first leaves the page, in one
 * request that outlives the page (`keepalive`). It measures only on an instance whose operator turned
 * page experience metrics on, and only for a visit that started on a signed-in page outside a user
 * view: the server counts only signed-in readers, and a refused report would start session recovery.
 * Until both are known, the app does not even load `web-vitals`, whose module observes resource
 * timings when it loads. Once loaded, `web-vitals` reads the timings that the browser buffered since
 * the page opened. A visit counts from then: if the reader left before, the next time they leave
 * sends it. A visit is the document's life until the reader leaves, so INP and CLS cover every page
 * the reader moved to, and each is named after the page the visit started on. A page restored from
 * the back-forward cache reports nothing more: `web-vitals` starts late and readies its own restore
 * handling only after timers that a restore can overtake, so a restored visit could carry the first
 * load's timings.
 */
export function reportVisitVitals(router: AnyRouter, queryClient: QueryClient) {
	const pageNow = () => {
		const routeIds = router.state.matches.map(({ routeId }) => routeId);
		return routeIds.includes(SIGNED_IN) ? journeyPageOf(routeIds) : null;
	};
	/** `null` until the landing page resolves, and for a visit that does not report. */
	let page: WebVital["page"] | null = null;
	let measuring = false;
	const measured: Measurements = new Map();
	/** The visit has reported, or the page came back from the back-forward cache. */
	let done = false;
	addEventListener("pageshow", (event) => {
		if (event.persisted) {
			done = true;
		}
	});
	// `web-vitals` reports CLS and INP when the page hides; this listener runs after it.
	document.addEventListener("visibilitychange", () => {
		queueMicrotask(() => {
			if (document.visibilityState !== "hidden" || !measuring || done) {
				return;
			}
			done = true;
			const landing = page;
			if (landing !== null && measured.size > 0) {
				const vitals = [...measured.values()].map((vital) => ({ ...vital, page: landing }));
				void reportWebVitals({ body: { vitals }, keepalive: true });
			}
		});
	});
	const collect = (metric: Measured) => {
		if (!done && page !== null) {
			measured.set(metric.name, {
				vital: metric.name,
				region: regionOf(metric),
				value: metric.value,
			});
		}
	};
	const measure = async () => {
		const { onCLS, onINP, onLCP, onTTFB } = await import("web-vitals/attribution");
		const options = { generateTarget: landmarkOf };
		onLCP(collect, options);
		onINP(collect, options);
		onCLS(collect, options);
		onTTFB(collect);
		measuring = true;
	};
	const unsubscribe = router.subscribe("onResolved", () => {
		unsubscribe();
		page = pageNow();
		// A user view shows another person's pages; the administrator's visit is no reader's.
		if (!done && page !== null && getUserViewSession() === undefined) {
			// While the app's own read of the flags is fresh, this shares it.
			queryClient
				.query({ ...featureFlagsQueryOptions, retry: false })
				.then(async (flags) => {
					if (flags.PAGE_EXPERIENCE_METRICS) {
						await measure();
					}
				})
				.catch(noop);
		}
	});
}
