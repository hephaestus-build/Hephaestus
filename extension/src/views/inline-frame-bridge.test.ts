// @vitest-environment jsdom
import { QueryClientProvider } from "@tanstack/react-query";
import { browser } from "@wxt-dev/browser";
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { frameToPageSchema, MAX_FRAME_HEIGHT } from "~/shared/frame-messages";
import type { RpcRequest } from "~/shared/rpc";
import { required } from "~/testing/required";
import { createQueryClient } from "~/ui/worker-state";
import { InlineView } from "~/views/InlineView";

vi.mock("@wxt-dev/browser", () => ({
	browser: {
		runtime: {
			id: "test-extension",
			getURL: (path: string) => `chrome-extension://test-extension${path}`,
			onMessage: { addListener: () => undefined },
			sendMessage: async (request: RpcRequest) => ({
				ok: true,
				generation: 0,
				data:
					request.type === "get-state"
						? { generation: 0, developmentBuild: false, session: { status: "signed-out" } }
						: { status: "not-configured" },
			}),
		},
	},
}));

const OPEN = "12345678-1234-1234-1234-123456789abc";
const PARENT = "https://gitlab.example.test";
const observers: FrameObserver[] = [];

/** Only layout delivery is simulated; the view, bridge and protocol are the actual sources. */
class FrameObserver implements ResizeObserver {
	readonly onResize: ResizeObserverCallback;
	element: Element | undefined;

	constructor(onResize: ResizeObserverCallback) {
		this.onResize = onResize;
		observers.push(this);
	}

	observe(element: Element): void {
		this.element = element;
	}

	unobserve(): void {
		this.element = undefined;
	}

	disconnect(): void {
		this.element = undefined;
	}

	resize(height: number): void {
		if (this.element === undefined) {
			throw new Error("The frame did not subscribe to layout changes");
		}
		Object.defineProperty(this.element, "scrollHeight", { configurable: true, value: height });
		this.onResize([], this);
	}
}

/** Whether the frame is on screen, switchable: the report scrolls away with the page. */
type OnScreenChange = (entries: Pick<IntersectionObserverEntry, "isIntersecting">[]) => void;
const screens: OnScreenChange[] = [];

function screenObserver(onChange: OnScreenChange) {
	screens.push(onChange);
	return { observe: () => undefined, disconnect: () => undefined };
}

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
	vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
	vi.stubGlobal("ResizeObserver", FrameObserver);
	vi.stubGlobal("IntersectionObserver", vi.fn(screenObserver));
	screens.length = 0;
	vi.stubGlobal("location", { ancestorOrigins: [PARENT], search: `?open=${OPEN}` });
	observers.length = 0;
	container = document.createElement("div");
	document.body.append(container);
	root = createRoot(container);
});

afterEach(async () => {
	await act(async () => root.unmount());
	container.remove();
	vi.restoreAllMocks();
	vi.unstubAllGlobals();
});

/** Whether a message the frame sent is a read of its tab's context. */
function isContextRead(request: unknown): boolean {
	return (
		typeof request === "object" &&
		request !== null &&
		"type" in request &&
		request.type === "get-context"
	);
}

it("keeps tall content resize messages usable and resizes down again when it collapses", async () => {
	const post = vi.spyOn(window.parent, "postMessage").mockReturnValue(undefined);
	const client = createQueryClient();
	await act(async () => {
		root.render(createElement(QueryClientProvider, { client }, createElement(InlineView)));
	});
	expect(post).toHaveBeenCalledWith({ type: "hephaestus:ready", open: OPEN }, PARENT);
	const observer = required(observers[0], "the frame's resize observer");
	// Many practices, expanded admin rows or narrow-width wrapping exceed the viewport limit.
	observer.resize(MAX_FRAME_HEIGHT + 1800);
	expect(post).toHaveBeenLastCalledWith(
		{ type: "hephaestus:size", open: OPEN, height: MAX_FRAME_HEIGHT },
		PARENT,
	);
	// The exact content height: the frame has no border of its own; the page's panel draws it.
	observer.resize(320);
	expect(post).toHaveBeenLastCalledWith(
		{ type: "hephaestus:size", open: OPEN, height: 320 },
		PARENT,
	);
	for (const [message] of post.mock.calls) {
		expect(frameToPageSchema.safeParse(message).success).toBe(true);
	}
	client.clear();
});

it("stops asking while the report is scrolled out of view, and asks again once it is back", async () => {
	vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"] });
	try {
		const send = vi.spyOn(browser.runtime, "sendMessage");
		// The mocked runtime receives the RPC objects; the spy's overload types them as strings.
		const calls = (): unknown[][] => send.mock.calls;
		const contextReads = () => calls().filter(([request]) => isContextRead(request)).length;
		const client = createQueryClient();
		await act(async () => {
			root.render(createElement(QueryClientProvider, { client }, createElement(InlineView)));
		});
		await act(async () => vi.advanceTimersByTimeAsync(0));
		const first = contextReads();
		expect(first).toBeGreaterThan(0);
		await act(async () => vi.advanceTimersByTimeAsync(60_000));
		expect(contextReads()).toBe(first + 1);

		const screen = required(screens[0], "the frame's on-screen watch");
		await act(async () => {
			screen([{ isIntersecting: false }]);
		});
		await act(async () => vi.advanceTimersByTimeAsync(180_000));
		expect(contextReads()).toBe(first + 1);

		await act(async () => {
			screen([{ isIntersecting: true }]);
		});
		await act(async () => vi.advanceTimersByTimeAsync(60_000));
		expect(contextReads()).toBe(first + 2);
		client.clear();
	} finally {
		vi.useRealTimers();
	}
});
