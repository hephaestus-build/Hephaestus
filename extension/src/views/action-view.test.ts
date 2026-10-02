// @vitest-environment jsdom
import { QueryClientProvider } from "@tanstack/react-query";
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import type { ActionPreview } from "~/shared/review-actions";
import type { RpcEvent, RpcRequest } from "~/shared/rpc";
import { required } from "~/testing/required";
import { createQueryClient } from "~/ui/worker-state";
import { ActionView } from "~/views/ActionView";

const platform = vi.hoisted(() => ({
	sendMessage: vi.fn<(request: RpcRequest) => Promise<unknown>>(),
	addListener:
		vi.fn<(listener: (event: RpcEvent, sender: { id: string; url: string }) => void) => void>(),
}));
vi.mock("@wxt-dev/browser", () => ({
	browser: {
		runtime: {
			id: "test-extension",
			getURL: (path: string) => `chrome-extension://test-extension${path}`,
			sendMessage: platform.sendMessage,
			onMessage: { addListener: platform.addListener },
		},
	},
}));

const INTENT = "00000000-0000-4000-8000-000000000001";
const PREVIEW: ActionPreview = {
	action: { kind: "request-review" },
	instanceHost: "private-instance.test",
	workspace: { slug: "private-team", displayName: "Confidential workspace" },
	work: {
		id: "16",
		kind: "scm.pull_request",
		provider: "GITHUB",
		label: "#1",
		title: "Private work title",
	},
};
let container: HTMLDivElement;
let root: Root;
let generation = 0;

async function until(assertion: () => void): Promise<void> {
	await act(async () => vi.waitFor(assertion));
}
async function changed(): Promise<void> {
	generation += 1;
	await act(async () => {
		const listener = platform.addListener.mock.calls[0]?.[0];
		if (listener === undefined) {
			throw new Error("No worker listener");
		}
		listener(
			{ type: "state-changed", generation },
			{ id: "test-extension", url: "chrome-extension://test-extension/background.js" },
		);
	});
}
async function render(): Promise<void> {
	await act(async () =>
		root.render(
			createElement(
				QueryClientProvider,
				{ client: createQueryClient() },
				createElement(ActionView),
			),
		),
	);
}
function invalid() {
	return {
		ok: false,
		generation,
		error: { code: "expired", message: "Start again from the current account." },
	};
}
function expectPrivateGone(): void {
	expect(container.textContent).not.toContain("Confidential workspace");
	expect(container.textContent).not.toContain("Private work title");
	expect(container.textContent).not.toContain("private-instance.test");
}

/**
 * The worker's answer to the window: `confirmed` for the confirmation itself, and for anything else
 * the preview — or, once the account changed, `otherwise`.
 */
async function confirmOr(
	request: { type: string },
	confirmed: unknown,
	otherwise?: unknown,
): Promise<unknown> {
	if (request.type === "confirm-action") {
		return confirmed;
	}
	return otherwise ?? { ok: true, generation, data: PREVIEW };
}

beforeEach(() => {
	vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
	platform.sendMessage.mockReset();
	generation += 10;
	location.hash = INTENT;
	container = document.createElement("div");
	document.body.append(container);
	root = createRoot(container);
});
afterEach(async () => {
	await act(async () => root.unmount());
	container.remove();
	vi.unstubAllGlobals();
});

it("clears the private preview on sign-out and does not restore an in-flight mutation's result", async () => {
	const reply = Promise.withResolvers<unknown>();
	platform.sendMessage.mockImplementation(async (request) => confirmOr(request, reply.promise));
	await render();
	await until(() => expect(container.textContent).toContain("Private work title"));
	const confirm = required(
		[...container.querySelectorAll("button")].find((button) =>
			button.textContent.includes("Request review"),
		),
		"the confirmation button",
	);
	const originalGeneration = generation;
	await act(async () => confirm.click());
	await until(() =>
		expect(
			platform.sendMessage.mock.calls.some(([request]) => request.type === "confirm-action"),
		).toBe(true),
	);
	platform.sendMessage.mockImplementation(async (request) =>
		confirmOr(request, reply.promise, invalid()),
	);
	await changed();
	expectPrivateGone();
	await act(async () =>
		reply.resolve({
			ok: true,
			generation: originalGeneration,
			data: { kind: "request-review", status: "SUBMITTED" },
		}),
	);
	await until(() =>
		expect(container.textContent).toContain("Start again from the current account."),
	);
	expectPrivateGone();
	expect(
		platform.sendMessage.mock.calls.filter(([request]) => request.type === "confirm-action"),
	).toHaveLength(1);
});

it("does not resurrect a delayed old-account preview after invalidation", async () => {
	const old = Promise.withResolvers<unknown>();
	platform.sendMessage.mockImplementationOnce(async () => old.promise);
	platform.sendMessage.mockImplementation(async () => invalid());
	await render();
	const originalGeneration = generation;
	await changed();
	await act(async () => old.resolve({ ok: true, generation: originalGeneration, data: PREVIEW }));
	await until(() =>
		expect(container.textContent).toContain("Start again from the current account."),
	);
	expectPrivateGone();
});

it("discards a previous intent's successful result when the same window receives another intent", async () => {
	platform.sendMessage.mockImplementation(async (request) =>
		confirmOr(request, {
			ok: true,
			generation,
			data: { kind: "request-review", status: "SUBMITTED" },
		}),
	);
	await render();
	await until(() => expect(container.textContent).toContain("Private work title"));
	const confirm = required(
		[...container.querySelectorAll("button")].find((button) =>
			button.textContent.includes("Request review"),
		),
		"the confirmation button",
	);
	await act(async () => confirm.click());
	await until(() =>
		expect(
			platform.sendMessage.mock.calls.some(([request]) => request.type === "confirm-action"),
		).toBe(true),
	);
	platform.sendMessage.mockImplementation(async () => invalid());
	await act(async () => {
		location.hash = "00000000-0000-4000-8000-000000000002";
		window.dispatchEvent(new HashChangeEvent("hashchange"));
	});
	await until(() =>
		expect(container.textContent).toContain("Start again from the current account."),
	);
	expectPrivateGone();
});
