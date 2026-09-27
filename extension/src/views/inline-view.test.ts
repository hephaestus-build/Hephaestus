// @vitest-environment jsdom
import { QueryClientProvider } from "@tanstack/react-query";
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import {
	NEGATIVE_ROW,
	OWN_PAGE,
	READY_ON_GITHUB,
	WORK_FEEDBACK,
} from "~/components/report/fixtures";
import type { RpcRequest, RpcResult } from "~/shared/rpc";
import { createQueryClient } from "~/ui/worker-state";
import { InlineView } from "~/views/InlineView";

/** What the worker answers each command with; a test swaps one to make a refresh fail. */
const answers = new Map<RpcRequest["type"], () => RpcResult<unknown>>();
/** Every request the frame sent, in order. */
const sent: RpcRequest[] = [];

function ok(data: unknown): () => RpcResult<unknown> {
	return () => ({ ok: true, data, generation: 3 });
}

vi.mock("@wxt-dev/browser", () => ({
	browser: {
		runtime: {
			id: "test-extension",
			getURL: (path: string) => `chrome-extension://test-extension${path}`,
			onMessage: { addListener: () => undefined },
			sendMessage: async (request: RpcRequest) => {
				sent.push(request);
				return answers.get(request.type)?.();
			},
		},
	},
}));

/** Layout and visibility are not what this test is about; the observers report nothing. */
function inertObserver() {
	return { observe: () => undefined, disconnect: () => undefined };
}

let container: HTMLDivElement;
let root: Root;

/** What a list row's preview reads, and what only the work's own page may ask for. */
const ROW_READS = new Set<RpcRequest["type"]>(["get-context", "get-work-feedback"]);
function subjectOf(request: RpcRequest): unknown {
	return "subject" in request ? request.subject : undefined;
}

const PAGE_ONLY = new Set<RpcRequest["type"]>([
	"list-observations",
	"open-action",
	"get-report-view",
]);

beforeEach(() => {
	vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
	vi.stubGlobal("ResizeObserver", vi.fn(inertObserver));
	vi.stubGlobal("IntersectionObserver", vi.fn(inertObserver));
	vi.stubGlobal("location", { ancestorOrigins: [], search: "" });
	answers.clear();
	sent.length = 0;
	answers.set(
		"get-state",
		ok({ generation: 3, developmentBuild: false, session: { status: "signed-out" } }),
	);
	answers.set("get-report-view", ok({ expanded: true }));
	answers.set("get-context", ok(READY_ON_GITHUB));
	answers.set("get-work-feedback", ok(WORK_FEEDBACK));
	container = document.createElement("div");
	document.body.append(container);
	root = createRoot(container);
});

afterEach(async () => {
	await act(async () => root.unmount());
	container.remove();
	vi.unstubAllGlobals();
});

it("says a failed feedback refresh failed, rather than keep old comments under a fresh context", async () => {
	const client = createQueryClient();
	await act(async () => {
		root.render(createElement(QueryClientProvider, { client }, createElement(InlineView)));
	});
	await vi.waitFor(() => expect(container.textContent).toContain("Descriptive merge request"));

	answers.set("get-work-feedback", () => ({
		ok: false,
		error: { code: "server", message: "Hephaestus ran into a problem." },
		generation: 3,
	}));
	await act(async () => {
		await client.refetchQueries();
	});
	await vi.waitFor(() => expect(container.textContent).toContain("Hephaestus ran into a problem."));
	// The context refreshed fine; the feedback did not, and none of the old comments remain.
	expect(container.textContent).toContain("Your feedback could not load");
	expect(container.textContent).not.toContain("Descriptive merge request");
	expect(container.querySelector("[role=alert]")).not.toBeNull();
	client.clear();
});

it("asks for the reader's observations only once the report is open", async () => {
	answers.set("get-report-view", ok({ expanded: false }));
	answers.set("list-observations", ok(OWN_PAGE));
	const client = createQueryClient();
	await act(async () => {
		root.render(createElement(QueryClientProvider, { client }, createElement(InlineView)));
	});
	await vi.waitFor(() =>
		expect(sent.some((request) => request.type === "get-work-feedback")).toBe(true),
	);
	expect(sent.some((request) => request.type === "list-observations")).toBe(false);
	const toggle = container.querySelector<HTMLButtonElement>("button[aria-expanded=false]");
	await act(async () => {
		toggle?.click();
	});
	await vi.waitFor(() => expect(container.textContent).toContain(NEGATIVE_ROW.summary));
	expect(sent.filter((request) => request.type === "list-observations")).toHaveLength(1);
	client.clear();
});

it("reads about a list row only as that row, and keeps no preference for it", async () => {
	const row = "https://github.com/HephaestusTest/lifecycle-validation/pull/1";
	vi.stubGlobal("location", { ancestorOrigins: [], search: `?work=${encodeURIComponent(row)}` });
	const client = createQueryClient();
	await act(async () => {
		root.render(createElement(QueryClientProvider, { client }, createElement(InlineView)));
	});
	await vi.waitFor(() =>
		expect(sent.some((request) => request.type === "get-work-feedback")).toBe(true),
	);
	const reads = sent.filter((request) => ROW_READS.has(request.type));
	expect(reads.length).toBeGreaterThanOrEqual(2);
	expect(new Set(reads.map((request) => JSON.stringify(subjectOf(request))))).toStrictEqual(
		new Set([JSON.stringify({ kind: "list-row", url: row })]),
	);
	expect(sent.some((request) => request.type === "get-report-view")).toBe(false);
	// The preview is its one line, shown at once: nothing to open, nothing more to ask for.
	await vi.waitFor(() => expect(container.textContent).toContain("3 comments for you"));
	expect(container.querySelector("[aria-expanded]")).toBeNull();
	expect(sent.filter((request) => PAGE_ONLY.has(request.type))).toStrictEqual([]);
	client.clear();
});

it("says a list row's preview is not current when its refresh fails, and keeps the retry on the line", async () => {
	const row = "https://github.com/HephaestusTest/lifecycle-validation/pull/1";
	vi.stubGlobal("location", { ancestorOrigins: [], search: `?work=${encodeURIComponent(row)}` });
	const client = createQueryClient();
	await act(async () => {
		root.render(createElement(QueryClientProvider, { client }, createElement(InlineView)));
	});
	await vi.waitFor(() => expect(container.textContent).toContain("3 comments for you"));
	answers.set("get-context", () => ({
		ok: false,
		error: { code: "network", message: "Hephaestus could not be reached." },
		generation: 3,
	}));
	await act(async () => {
		await client.refetchQueries({ queryKey: ["context"] });
	});
	await vi.waitFor(() => expect(container.textContent).toContain("Not refreshed"));
	// What it knew stays, marked, and the one way on is on the line.
	expect(container.textContent).toContain("3 comments for you");
	expect(
		[...container.querySelectorAll("button")].map((button) => button.textContent),
	).toStrictEqual(["Try again"]);
	client.clear();
});
