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

/** How many route views the reporter remembers, to name the page of a measurement that arrives late. */
const REMEMBERED_VIEWS = 50;
/** The most measurements that one report carries. The server refuses a longer list, so a full batch goes at once. */
const REPORT_LIMIT = 40;

/** A page address without its fragment, as the browser names a navigation. */
const addressOf = (href: string) => {
	const url = new URL(href, location.href);
	url.hash = "";
	return url.href;
};

/**
 * Measures how a document's page loads and route views felt, and sends each measurement once. The
 * app measures only on an instance whose operator turned page experience metrics on, and only for a
 * document that started on a signed-in page outside a user view: the server counts only signed-in
 * readers, and a refused report would start session recovery. Until both are known, the app does not
 * load `web-vitals`, whose module observes resource timings when it loads.
 *
 * With `reportSoftNavs`, a browser that detects route changes (Chromium) measures each route view as
 * its own `ROUTE` navigation, and the first view as the `LOAD` navigation. Another browser measures
 * the whole document as one `LOAD`. `web-vitals` reports a navigation's INP and CLS when the next
 * navigation starts, or when the page hides. The first report of each metric is the one sent: a
 * histogram cannot replace a value, so a later report of the same metric is dropped. A page that the
 * back-forward cache restores reports nothing more: `web-vitals` starts late and readies its own
 * restore handling only after timers that a restore can overtake.
 */
export function reportVisitVitals(router: AnyRouter, queryClient: QueryClient) {
	const pageNow = () => {
		const routeIds = router.state.matches.map(({ routeId }) => routeId);
		return routeIds.includes(SIGNED_IN) ? journeyPageOf(routeIds) : null;
	};
	/** `null` until the landing page resolves, and for a document that does not report. */
	let landing: WebVital["page"] | null = null;
	let landed = false;
	let measuring = false;
	let restored = false;
	const pages = new Map<string, WebVital["page"]>();
	const reported = new Set<string>();
	const queued: WebVital[] = [];
	const send = () => {
		if (measuring && queued.length > 0) {
			void reportWebVitals({ body: { vitals: queued.splice(0) }, keepalive: true });
		}
	};
	const forgetRestored = () => {
		restored = true;
		queued.length = 0;
	};
	addEventListener("pageshow", (event) => {
		if (event.persisted) {
			forgetRestored();
		}
	});
	// `web-vitals` reports CLS and INP when the page hides; this listener runs after it.
	document.addEventListener("visibilitychange", () => {
		queueMicrotask(() => {
			if (document.visibilityState === "hidden" && !restored) {
				send();
			}
		});
	});
	const collect = (metric: Measured) => {
		// `web-vitals` reports a restored page from its own capture-phase `pageshow` listener, which
		// can run before the one above.
		if (metric.navigationType === "back-forward-cache") {
			forgetRestored();
		}
		if (restored || landing === null || reported.has(metric.id)) {
			return;
		}
		reported.add(metric.id);
		const navigation = metric.navigationType === "soft-navigation" ? "ROUTE" : "LOAD";
		// A route view has no document of its own, so its TTFB is always 0.
		if (metric.name === "TTFB" && navigation === "ROUTE") {
			return;
		}
		const page =
			navigation === "LOAD"
				? landing
				: (pages.get(addressOf(metric.navigationURL ?? "")) ?? "OTHER");
		queued.push({
			vital: metric.name,
			navigation,
			page,
			region: regionOf(metric),
			value: metric.value,
		});
		if (queued.length >= REPORT_LIMIT) {
			send();
		}
	};
	const measure = async () => {
		const { onCLS, onINP, onLCP, onTTFB } = await import("web-vitals/attribution");
		const options = { generateTarget: landmarkOf, reportSoftNavs: true };
		onLCP(collect, options);
		onINP(collect, options);
		onCLS(collect, options);
		onTTFB(collect, { reportSoftNavs: true });
		measuring = true;
	};
	router.subscribe("onResolved", () => {
		const page = pageNow();
		if (page !== null) {
			pages.delete(addressOf(location.href));
			pages.set(addressOf(location.href), page);
			if (pages.size > REMEMBERED_VIEWS) {
				pages.delete(pages.keys().next().value ?? "");
			}
		}
		if (landed) {
			return;
		}
		landed = true;
		// A user view shows another person's pages; the administrator's visit is no reader's.
		if (restored || page === null || getUserViewSession() !== undefined) {
			return;
		}
		landing = page;
		// While the app's own read of the flags is fresh, this shares it.
		queryClient
			.query({ ...featureFlagsQueryOptions, retry: false })
			.then(async (flags) => {
				if (flags.PAGE_EXPERIENCE_METRICS) {
					await measure();
				}
			})
			.catch(noop);
	});
}
