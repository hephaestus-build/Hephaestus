import { noop } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";
import type { RootOptions } from "react-dom/client";

import type { Client } from "@/api/client";
import { reportBrowserFaults } from "@/api/sdk.gen";
import type { BrowserFault, Frame } from "@/api/types.gen";
import environment from "@/environment";
import { problemStatusOf } from "@/lib/problem-detail";
import { journeyPageOf } from "@/runtime/web-vitals";

/** The most distinct faults that one document reports. A page that keeps failing adds counts, not reports. */
const MAX_FAULTS = 20;
/** The server accepts a count up to this. */
const MAX_COUNT = 100;
const MAX_FRAMES = 10;
/** A fault waits this long for others before the page sends them, unless the page hides first. */
const IDLE_MS = 5000;
/**
 * The most reports that one document sends, one at a time. Requests that outlive their page share a
 * 64 KiB budget, so a page that keeps failing stops reporting instead of crowding out the rest.
 */
const MAX_REPORTS = 10;

const ERROR_TYPES: Record<string, BrowserFault["errorType"]> = {
	TypeError: "TYPE_ERROR",
	ReferenceError: "REFERENCE_ERROR",
	SyntaxError: "SYNTAX_ERROR",
	RangeError: "RANGE_ERROR",
};

/** The class of a failure, reduced to the closed set that the server accepts. */
export function errorTypeOf(error: unknown): BrowserFault["errorType"] {
	return error instanceof Error ? (ERROR_TYPES[error.name] ?? "OTHER") : "OTHER";
}

/** A script position in a stack line, in Chromium's and in Firefox's and Safari's format. */
const POSITION = /(?<address>https?:\/\/[^\s()@]+):(?<line>\d+):(?<column>\d+)/gu;
/** A built script: `/assets/<name>-<hash>.js`, where the hash is the last eight characters. */
const CHUNK = /^\/assets\/.+(?<hash>[A-Za-z0-9_-]{8})\.js$/u;

/**
 * The frames of a failure that point into this web app's own build, as the content hash of the
 * script and a position in it. Frames of extensions, other origins and inline scripts are left out,
 * so a frame names nothing but our build.
 */
export function framesOf(error: unknown, origin = location.origin): Frame[] {
	const stack = error instanceof Error ? (error.stack ?? "") : "";
	const frames: Frame[] = [];
	for (const { groups } of stack.matchAll(POSITION)) {
		const address = URL.parse(groups?.address ?? "");
		const chunk =
			address?.origin === origin ? CHUNK.exec(address.pathname)?.groups?.hash : undefined;
		if (chunk !== undefined) {
			frames.push({ chunk, line: Number(groups?.line), column: Number(groups?.column) });
		}
		if (frames.length === MAX_FRAMES) {
			break;
		}
	}
	return frames;
}

/** What a failure adds to its fault, beyond its error. */
interface Failure {
	errorType?: BrowserFault["errorType"];
	traceId?: string;
	status?: number;
}

/** The page's fault reporter. `report` does nothing while the operator's switch is off. */
export interface BrowserFaults {
	report: (kind: BrowserFault["kind"], error: unknown, failure?: Failure) => void;
	uninstall: () => void;
}

const OFF: BrowserFaults = { report: noop, uninstall: noop };

/**
 * Reports the failures that a reader meets on this page to the server, when the operator turned
 * browser fault reports on. A report names the kind of failure, the journey page, the class of the
 * error, the release and frame positions, and for a failed request its trace id and status. It never
 * carries the error's message, the page address or who the reader is. An error that two surfaces see,
 * such as a failed request that a route then shows, is reported once. A failure to send is dropped:
 * a fault about a fault would only repeat it.
 */
export function installBrowserFaults(router: AnyRouter): BrowserFaults {
	if (!environment.browserFaultReports) {
		return OFF;
	}
	const seen = new Set<string>();
	const queued = new Map<string, BrowserFault>();
	const reportedErrors = new WeakSet();
	let idle: ReturnType<typeof setTimeout> | undefined;
	let sending = false;
	let reports = 0;
	const send = () => {
		clearTimeout(idle);
		if (sending || queued.size === 0 || reports >= MAX_REPORTS) {
			return;
		}
		sending = true;
		reports += 1;
		const faults = [...queued.values()];
		queued.clear();
		const sent = () => {
			sending = false;
			// Faults that arrived meanwhile wait for the next quiet moment.
			if (queued.size > 0) {
				idle = setTimeout(send, IDLE_MS);
			}
		};
		reportBrowserFaults({ body: { faults }, keepalive: true }).finally(sent).catch(noop);
	};
	const report = (kind: BrowserFault["kind"], error: unknown, failure: Failure = {}) => {
		if (typeof error === "object" && error !== null) {
			if (reportedErrors.has(error)) {
				return;
			}
			reportedErrors.add(error);
		}
		const fault: BrowserFault = {
			kind,
			page: journeyPageOf(router.state.matches.map(({ routeId }) => routeId)),
			errorType: failure.errorType ?? errorTypeOf(error),
			// The build commit tells two builds of one version apart, such as a stale tab after a deploy.
			release: environment.buildInfo.commit || environment.version,
			frames: framesOf(error),
			count: 1,
			traceId: failure.traceId,
			status: failure.status,
		};
		// A failed request has no stack of its own, so its trace and status tell two failures apart.
		const fingerprint = JSON.stringify([
			fault.kind,
			fault.page,
			fault.errorType,
			fault.frames[0],
			fault.status,
			fault.traceId,
		]);
		const same = queued.get(fingerprint);
		if (same !== undefined) {
			same.count = Math.min(same.count + 1, MAX_COUNT);
		} else if (seen.has(fingerprint) || seen.size < MAX_FAULTS) {
			seen.add(fingerprint);
			queued.set(fingerprint, fault);
		}
		clearTimeout(idle);
		idle = setTimeout(send, IDLE_MS);
	};
	const uncaught = (event: ErrorEvent) => {
		report("UNCAUGHT_ERROR", event.error);
	};
	const unhandled = (event: PromiseRejectionEvent) => {
		report("UNHANDLED_REJECTION", event.reason);
	};
	const hidden = () => {
		if (document.visibilityState === "hidden") {
			send();
		}
	};
	addEventListener("error", uncaught);
	addEventListener("unhandledrejection", unhandled);
	document.addEventListener("visibilitychange", hidden);
	return {
		report,
		uninstall: () => {
			clearTimeout(idle);
			removeEventListener("error", uncaught);
			removeEventListener("unhandledrejection", unhandled);
			document.removeEventListener("visibilitychange", hidden);
		},
	};
}

/**
 * React's root error handlers, which see every error that a component throws: one that no boundary
 * caught, one that a boundary caught and showed, the router's boundaries included, and one that React
 * recovered from. A caught error with an HTTP status is a failed request, which the request's own
 * report names; a 404 or 403 view is a state, not a failure.
 */
export function rootErrorHandlers(faults: BrowserFaults): RootOptions {
	return {
		onUncaughtError: (error, errorInfo) => {
			faults.report("UNCAUGHT_ERROR", error);
			// oxlint-disable-next-line no-console -- The custom handler replaces React's console report.
			console.warn("Uncaught error", error, errorInfo.componentStack);
		},
		onCaughtError: (error, errorInfo) => {
			if (problemStatusOf(error) === undefined) {
				faults.report("RENDER_ERROR", error);
			}
			// oxlint-disable-next-line no-console -- The custom handler replaces React's console report.
			console.warn("Caught error", error, errorInfo.componentStack);
		},
		onRecoverableError: (error, errorInfo) => {
			faults.report("RECOVERED_ERROR", error);
			// oxlint-disable-next-line no-console -- The custom handler replaces React's console report.
			console.warn("Recovered error", error, errorInfo.componentStack);
		},
	};
}

/** The paths of the reports themselves: a failed report is never reported. */
const REPORTS = /\/(?:browser-faults|web-vitals)$/u;

/**
 * Reports each request of the generated client that failed with a 5xx status, with the trace id that
 * the server returned as `X-Request-Id`, and each one that never reached the server. An aborted
 * request is the reader moving on, not a failure. Returns the function that removes the reporting.
 */
export function reportFailedRequests(client: Client, faults: BrowserFaults): () => void {
	const id = client.interceptors.error.use((failure, response, request) => {
		if (request !== undefined && !REPORTS.test(new URL(request.url).pathname)) {
			if (response !== undefined && response.status >= 500) {
				faults.report("REQUEST_FAILED", failure, {
					errorType: "HTTP_5XX",
					traceId: TRACE_ID.exec(response.headers.get("X-Request-Id") ?? "")?.[0],
					status: response.status,
				});
			} else if (
				response === undefined &&
				!(failure instanceof DOMException && failure.name === "AbortError")
			) {
				faults.report("REQUEST_FAILED", failure, { errorType: "NETWORK" });
			}
		}
		return failure;
	});
	return () => {
		client.interceptors.error.eject(id);
	};
}

const TRACE_ID = /^[0-9a-f]{32}$/u;
