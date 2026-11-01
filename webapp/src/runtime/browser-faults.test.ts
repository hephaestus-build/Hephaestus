import { noop } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { BrowserFaultsReport } from "@/api/types.gen";
import { zBrowserFaultsReport } from "@/api/zod.gen";
import type environment from "@/environment";
import { server } from "@/mocks/server";
import { deferred, sleep } from "@/test/async";

import { errorTypeOf, framesOf, installBrowserFaults } from "./browser-faults";

const operator = vi.hoisted(() => ({ browserFaultReports: true, commit: "" }));
vi.mock("@/environment", async (original) => {
	const { default: actual } = await original<{ default: typeof environment }>();
	return {
		default: {
			...actual,
			version: "0.95.1",
			buildInfo: {
				...actual.buildInfo,
				get commit() {
					return operator.commit;
				},
			},
			get browserFaultReports() {
				return operator.browserFaultReports;
			},
		},
	};
});

const ORIGIN = location.origin;
const FEEDBACK = [
	"__root__",
	"/_authenticated",
	"/_authenticated/w/$workspaceSlug/feedback/$itemId",
];

/** An error thrown at `line:column` of a script, with a stack in Chromium's format. */
function thrown(name: string, script: string, line = 1, column = 2345) {
	const error = new Error("Cannot read the reader's private note");
	error.name = name;
	error.stack = `${name}: Cannot read the reader's private note\n    at render (${script}:${line}:${column})`;
	return error;
}

/** Collects the reports the server receives, and answers each with `status` once `answered` settles. */
function receive(status = 204, answered: Promise<void> = Promise.resolve()) {
	const sent: BrowserFaultsReport[] = [];
	server.use(
		http.post("*/browser-faults", async ({ request }) => {
			sent.push(zBrowserFaultsReport.parse(await request.json()));
			await answered;
			return new HttpResponse(null, { status });
		}),
	);
	return sent;
}

let uninstall: () => void = noop;
function install(...routeIds: string[]) {
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- A stand-in for the one router member the reporter reads.
	const router = {
		state: { matches: routeIds.map((routeId) => ({ routeId })) },
	} as unknown as AnyRouter;
	uninstall = installBrowserFaults(router).uninstall;
}

function rejected(reason: unknown) {
	dispatchEvent(Object.assign(new Event("unhandledrejection"), { reason }));
}

async function hide() {
	Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
	document.dispatchEvent(new Event("visibilitychange"));
	Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
	await Promise.resolve();
}

afterEach(() => {
	uninstall();
	operator.browserFaultReports = true;
	operator.commit = "";
	vi.useRealTimers();
});

describe("framesOf", () => {
	it("keeps only frames in this web app's build, as the script's hash and position", () => {
		const error = new Error("boom");
		error.stack = [
			"TypeError: boom",
			`    at render (${ORIGIN}/assets/-PracticeReviewDrawer-CRYwA6-M.js:12:345)`,
			"    at chrome-extension://abc/content.js:1:1",
			`    at load (https://cdn.example.org/assets/vendor-DqCAn3iK.js:2:3)`,
			`    at (${ORIGIN}/w/acme/feedback/42:5:6)`,
			`dispatch@${ORIGIN}/assets/index-B1x_9z0Q.js:7:8`,
		].join("\n");

		expect(framesOf(error, ORIGIN)).toStrictEqual([
			{ chunk: "CRYwA6-M", line: 12, column: 345 },
			{ chunk: "B1x_9z0Q", line: 7, column: 8 },
		]);
		expect(framesOf("a string, not an error", ORIGIN)).toStrictEqual([]);
	});
});

describe("errorTypeOf", () => {
	it("reduces a failure to the closed set of error types", () => {
		expect(errorTypeOf(new TypeError("x"))).toBe("TYPE_ERROR");
		expect(errorTypeOf(new RangeError("x"))).toBe("RANGE_ERROR");
		expect(errorTypeOf(new DOMException("x", "AbortError"))).toBe("OTHER");
		expect(errorTypeOf({ name: "TypeError" })).toBe("OTHER");
	});
});

describe("installBrowserFaults", () => {
	it("reports an uncaught error and an unhandled rejection without their message, when the page hides", async () => {
		const sent = receive();
		install(...FEEDBACK);

		dispatchEvent(
			new ErrorEvent("error", { error: thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`) }),
		);
		rejected(thrown("ReferenceError", `${ORIGIN}/assets/index-B1x_9z0Q.js`, 9, 10));
		await hide();

		await vi.waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]?.faults).toStrictEqual([
			{
				kind: "UNCAUGHT_ERROR",
				page: "FEEDBACK",
				errorType: "TYPE_ERROR",
				release: "0.95.1",
				frames: [{ chunk: "B1x_9z0Q", line: 1, column: 2345 }],
				count: 1,
			},
			{
				kind: "UNHANDLED_REJECTION",
				page: "FEEDBACK",
				errorType: "REFERENCE_ERROR",
				release: "0.95.1",
				frames: [{ chunk: "B1x_9z0Q", line: 9, column: 10 }],
				count: 1,
			},
		]);
		expect(JSON.stringify(sent)).not.toContain("private note");
	});

	it("counts a repeated fault once, sends after a quiet moment, and stops at twenty distinct faults", async () => {
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
		const sent = receive();
		install("__root__", "/login");

		for (const _ of Array.from({ length: 3 })) {
			rejected(thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`));
		}
		for (const line of Array.from({ length: 25 }, (_, index) => index + 2)) {
			rejected(thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`, line));
		}
		vi.advanceTimersByTime(5000);
		vi.useRealTimers();

		await vi.waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]?.faults).toHaveLength(20);
		expect(sent[0]?.faults[0]).toMatchObject({ page: "OTHER", count: 3 });
	});

	it("reports nothing about a report that the server refused", async () => {
		const sent = receive(500);
		install(...FEEDBACK);

		rejected(thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`));
		await hide();
		await vi.waitFor(() => expect(sent).toHaveLength(1));
		await hide();

		expect(sent).toHaveLength(1);
	});

	it("names the build by its commit when the image carries one", async () => {
		operator.commit = "b021b7be6e2cdc4a847fea42004833488c349e6f";
		const sent = receive();
		install(...FEEDBACK);

		rejected(thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`));
		await hide();

		await vi.waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]?.faults[0]?.release).toBe("b021b7be6e2cdc4a847fea42004833488c349e6f");
	});

	it("sends one report at a time, and at most ten from one page", async () => {
		const answer = deferred();
		const sent = receive(204, answer.promise);
		install(...FEEDBACK);

		rejected(thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`));
		await hide();
		await vi.waitFor(() => expect(sent).toHaveLength(1));
		rejected(thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`));
		await hide();
		await hide();
		// A second request would reach the server within this time.
		await sleep(50);
		expect(sent).toHaveLength(1);

		answer.resolve();
		await vi.waitFor(async () => {
			await hide();
			expect(sent).toHaveLength(2);
		});
		for (const expected of Array.from({ length: 10 }, (_, index) => index + 3)) {
			rejected(thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`));
			await vi.waitFor(async () => {
				await hide();
				expect(sent).toHaveLength(Math.min(expected, 10));
			});
		}

		expect(sent).toHaveLength(10);
	});

	it("listens to nothing until the operator turns browser fault reports on", async () => {
		operator.browserFaultReports = false;
		const sent = receive();
		install(...FEEDBACK);

		rejected(thrown("TypeError", `${ORIGIN}/assets/index-B1x_9z0Q.js`));
		await hide();

		expect(sent).toStrictEqual([]);
	});
});
