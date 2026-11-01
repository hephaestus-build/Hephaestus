import { noop } from "@tanstack/react-query";
import {
	type AnyRouter,
	createMemoryHistory,
	createRootRoute,
	createRoute,
	createRouter,
	RouterProvider,
} from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { act, Component, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createClient } from "@/api/client";
import { client as appClient } from "@/api/client.gen";
import type { BrowserFault, BrowserFaultsReport } from "@/api/types.gen";
import { zBrowserFaultsReport } from "@/api/zod.gen";
import type environment from "@/environment";
import { server } from "@/mocks/server";
import { installPreloadErrorReload } from "@/runtime/preload-error";
import { sleep } from "@/test/async";

import {
	type BrowserFaults,
	installBrowserFaults,
	reportFailedRequests,
	rootErrorHandlers,
} from "./browser-faults";

vi.mock("@/environment", async (original) => {
	const { default: actual } = await original<{ default: typeof environment }>();
	return { default: { ...actual, version: "0.95.1", browserFaultReports: true } };
});

const TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
const OTHER_TRACE = "00f067aa0ba902b7a3ce929d0e0e4736";

class Boundary extends Component<{ children: ReactNode }, { failed: boolean }> {
	override state = { failed: false };

	static getDerivedStateFromError() {
		return { failed: true };
	}

	override render() {
		return this.state.failed ? <p>Something went wrong</p> : <div>{this.props.children}</div>;
	}
}

function Throws({ error }: { error: unknown }): ReactNode {
	throw error;
}

/** Throws on its first render only, so React's retry renders it and React recovers. */
function throwsOnce() {
	let renders = 0;
	return function Flaky() {
		renders += 1;
		if (renders === 1) {
			throw new TypeError("x");
		}
		return <p>Recovered</p>;
	};
}

/** Renders `content` in a root with the app's error handlers, as `main.tsx` creates it. */
async function renderInRoot(faults: BrowserFaults, content: ReactNode) {
	const root = createRoot(document.createElement("div"), rootErrorHandlers(faults));
	await act(async () => {
		root.render(content);
	});
	root.unmount();
}

/**
 * Renders `content` as the browser does, outside `act`. Inside `act`, React throws an uncaught render
 * error at the caller instead of calling the root's `onUncaughtError`.
 */
async function renderOutsideAct(faults: BrowserFaults, content: ReactNode) {
	const actEnvironment: unknown = Reflect.get(globalThis, "IS_REACT_ACT_ENVIRONMENT");
	Reflect.set(globalThis, "IS_REACT_ACT_ENVIRONMENT", false);
	const root = createRoot(document.createElement("div"), rootErrorHandlers(faults));
	try {
		root.render(content);
		await sleep(50);
	} finally {
		root.unmount();
		Reflect.set(globalThis, "IS_REACT_ACT_ENVIRONMENT", actEnvironment);
	}
}

/** Renders a real router whose one route loads with `loader`, in a root with the app's handlers. */
async function renderRoute(faults: BrowserFaults, loader: () => Promise<void>) {
	const rootRoute = createRootRoute();
	const router = createRouter({
		routeTree: rootRoute.addChildren([
			createRoute({
				getParentRoute: () => rootRoute,
				path: "/",
				loader,
				component: () => <p>Page</p>,
			}),
		]),
		history: createMemoryHistory({ initialEntries: ["/"] }),
	});
	await renderInRoot(faults, <RouterProvider router={router} />);
}

/** A failure that the server answers with a problem body, its status and a trace id. */
function failing(path: string, status: number, trace: string) {
	server.use(
		http.get(`*${path}`, () =>
			HttpResponse.json(
				{ status, title: "Unavailable" },
				{ status, headers: { "X-Request-Id": trace } },
			),
		),
	);
}

function routerAt(isLoading: boolean) {
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- A stand-in for the router members the surfaces read.
	return { state: { isLoading, matches: [] } } as unknown as AnyRouter;
}

type Source = (faults: BrowserFaults) => Promise<void>;

/**
 * Each kind of browser fault with every surface that reports it, fired as the browser, React or the
 * router fires it. A new kind in the wire contract fails the type check until it has a surface here.
 */
const surfaces = {
	UNCAUGHT_ERROR: {
		"the window": async () => {
			dispatchEvent(new ErrorEvent("error", { error: new TypeError("x") }));
		},
		"React's root": async (faults) => {
			await renderOutsideAct(faults, <Throws error={new TypeError("x")} />);
		},
	},
	UNHANDLED_REJECTION: {
		"the window": async () => {
			dispatchEvent(
				Object.assign(new Event("unhandledrejection"), { reason: new RangeError("x") }),
			);
		},
	},
	RENDER_ERROR: {
		"an error boundary": async (faults) => {
			await renderInRoot(
				faults,
				<Boundary>
					<Throws error={new TypeError("x")} />
				</Boundary>,
			);
		},
		"a route's loader": async (faults) => {
			await renderRoute(faults, async () => {
				throw new ReferenceError("x");
			});
		},
	},
	RECOVERED_ERROR: {
		"React's retry": async (faults) => {
			const Flaky = throwsOnce();
			await renderInRoot(faults, <Flaky />);
		},
	},
	CHUNK_LOAD_FAILED: {
		"a navigation whose route then shows the import error": async (faults) => {
			// A reload already claimed, later than any clock reads, means no reload now, so Vite throws the payload.
			sessionStorage.setItem("hephaestus.preload-error-reload-at", String(Number.MAX_SAFE_INTEGER));
			const uninstall = installPreloadErrorReload(routerAt(true), faults);
			const payload = new TypeError("Failed to fetch dynamically imported module");
			dispatchEvent(
				Object.assign(new Event("vite:preloadError", { cancelable: true }), { payload }),
			);
			uninstall();
			await renderInRoot(
				faults,
				<Boundary>
					<Throws error={payload} />
				</Boundary>,
			);
		},
	},
	REQUEST_FAILED: {
		"the generated client": async (faults) => {
			failing("/failing", 503, TRACE);
			const client = createClient({ baseUrl: "http://localhost:8080" });
			reportFailedRequests(client, faults);
			await client.get({ url: "/failing" });
		},
		"a route's loader that shows the failed request": async (faults) => {
			failing("/failing", 503, TRACE);
			const client = createClient({ baseUrl: "http://localhost:8080" });
			reportFailedRequests(client, faults);
			await renderRoute(faults, async () => {
				await client.get({ url: "/failing", throwOnError: true });
			});
		},
	},
} satisfies Record<BrowserFault["kind"], Record<string, Source>>;

/** Collects the faults the server receives, answering each report with `status`. */
function receive(status = 204) {
	const reports: BrowserFaultsReport[] = [];
	server.use(
		http.post("*/browser-faults", async ({ request }) => {
			reports.push(zBrowserFaultsReport.parse(await request.json()));
			return new HttpResponse(null, { status });
		}),
	);
	return reports;
}

async function hide() {
	Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
	document.dispatchEvent(new Event("visibilitychange"));
	Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
	await Promise.resolve();
}

let faults: BrowserFaults | undefined;
afterEach(() => {
	faults?.uninstall();
	sessionStorage.clear();
	vi.restoreAllMocks();
});

describe("every surface of a browser fault reports it once", () => {
	const cases = Object.entries(surfaces).flatMap(([kind, sources]) =>
		Object.entries(sources).map(([source, fire]: [string, Source]) => ({ kind, source, fire })),
	);
	it.each(cases)("$kind from $source", async ({ kind, fire }) => {
		vi.spyOn(console, "warn").mockReturnValue(undefined);
		const reports = receive();
		faults = installBrowserFaults(routerAt(false));

		await fire(faults);
		await hide();

		await vi.waitFor(() => expect(reports).toHaveLength(1));
		expect(reports.flatMap((report) => report.faults.map((fault) => fault.kind))).toStrictEqual([
			kind,
		]);
	});
});

describe("how a failed request is reported", () => {
	it("keeps two failures with their own trace and status, and reports a network failure but not an abort", async () => {
		const reports = receive();
		faults = installBrowserFaults(routerAt(false));
		failing("/failing", 500, TRACE);
		failing("/unavailable", 503, OTHER_TRACE);
		server.use(
			http.get("*/unreachable", () => HttpResponse.error()),
			http.post("*/web-vitals", () => new HttpResponse(null, { status: 503 })),
		);
		const client = createClient({ baseUrl: "http://localhost:8080" });
		reportFailedRequests(client, faults);
		const aborted = new AbortController();
		aborted.abort();

		await client.get({ url: "/failing" });
		await client.get({ url: "/unavailable" });
		await client.get({ url: "/unreachable" });
		await client.get({ url: "/failing", signal: aborted.signal });
		await client.post({ url: "/web-vitals" });
		await hide();

		await vi.waitFor(() => expect(reports).toHaveLength(1));
		expect(reports[0]?.faults).toMatchObject([
			{ kind: "REQUEST_FAILED", errorType: "HTTP_5XX", traceId: TRACE, status: 500, count: 1 },
			{
				kind: "REQUEST_FAILED",
				errorType: "HTTP_5XX",
				traceId: OTHER_TRACE,
				status: 503,
				count: 1,
			},
			{ kind: "REQUEST_FAILED", errorType: "NETWORK" },
		]);
	});

	it("reports nothing about its own report, even with the app's client reporting failed requests", async () => {
		const reports = receive(503);
		faults = installBrowserFaults(routerAt(false));
		const uninstall = reportFailedRequests(appClient, faults);
		try {
			dispatchEvent(new ErrorEvent("error", { error: new TypeError("x") }));
			await hide();
			await vi.waitFor(() => expect(reports).toHaveLength(1));
			await sleep(50);
			await hide();
			await sleep(50);

			expect(reports).toHaveLength(1);
		} finally {
			uninstall();
		}
	});

	it("leaves a not-found or no-access view and a failed request's view to the request", async () => {
		vi.spyOn(console, "warn").mockReturnValue(undefined);
		const reports = receive();
		faults = installBrowserFaults(routerAt(false));

		for (const status of [404, 403, 503]) {
			await renderInRoot(
				faults,
				<Boundary>
					<Throws error={{ status, title: "Problem" }} />
				</Boundary>,
			).catch(noop);
		}
		await hide();
		await sleep(50);

		expect(reports).toStrictEqual([]);
	});
});
