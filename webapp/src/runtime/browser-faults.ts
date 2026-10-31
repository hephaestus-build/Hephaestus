import { noop } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";

import { reportBrowserFaults } from "@/api/sdk.gen";
import type { BrowserFault, Frame } from "@/api/types.gen";
import environment from "@/environment";
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

/**
 * Reports the failures that a reader meets on this page to the server, when the operator turned
 * browser fault reports on. A report names the kind of failure, the journey page, the class of the
 * error, the release and frame positions. It never carries the error's message, the page address or
 * who the reader is. A failure to send is dropped: a fault about a fault would only repeat it.
 * Returns the function that removes the reporting.
 */
export function installBrowserFaults(router: AnyRouter): () => void {
	if (!environment.browserFaultReports) {
		return noop;
	}
	const seen = new Set<string>();
	const queued = new Map<string, BrowserFault>();
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
	const report = (kind: BrowserFault["kind"], error: unknown) => {
		const fault: BrowserFault = {
			kind,
			page: journeyPageOf(router.state.matches.map(({ routeId }) => routeId)),
			errorType: errorTypeOf(error),
			// The build commit tells two builds of one version apart, such as a stale tab after a deploy.
			release: environment.buildInfo.commit || environment.version,
			frames: framesOf(error),
			count: 1,
		};
		const fingerprint = JSON.stringify([fault.kind, fault.page, fault.errorType, fault.frames[0]]);
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
	return () => {
		clearTimeout(idle);
		removeEventListener("error", uncaught);
		removeEventListener("unhandledrejection", unhandled);
		document.removeEventListener("visibilitychange", hidden);
	};
}
