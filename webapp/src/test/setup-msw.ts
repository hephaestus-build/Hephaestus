// Global Vitest setup: stand up the MSW Node server so RTL tests hit deterministic mocked endpoints
// (the same handlers Storybook uses). Handlers reset after each test so a `server.use(...)` override
// in one test never leaks into the next.

import path from "node:path";

import {
	afterAll,
	afterEach,
	beforeAll,
	beforeEach,
	vi,
	type RunnerTask,
	type RunnerTestFile,
} from "vitest";
import { toast } from "@/lib/toast";

import { client } from "@/api/client.gen";
import { clearDrafts } from "@/lib/composer-drafts";
import { server, unansweredRequests } from "@/mocks/server";
import { FakeEventSource } from "@/test/fake-event-source";
import { ObserverStub } from "@/test/observers";
import { unansweredRequestDebt } from "@/test/unanswered-request-debt";

// DOM-only route tests exercise the form contract; the production runtime check uses real Monaco.
vi.mock("@/components/common/CodeEditor", async () => import("./code-editor.mock"));

// The generated hey-api client resolves request paths against `baseUrl` (it does
// `new URL(path, baseUrl)`); with no baseUrl a relative path like `/user` throws
// "Failed to parse URL". In the app `client.setConfig` runs in `main.tsx`, which the
// test bundle never imports — so configure an absolute base here. The MSW handlers use
// `*/path` wildcards, so this host is matched regardless of its exact value.
client.setConfig({ baseUrl: "http://localhost:8080" });

// jsdom has no ResizeObserver; Base UI's anchor positioning observes elements to keep a popup
// pinned to its trigger. A stub that never fires is enough — no assertion depends on the measurements.
vi.stubGlobal("ResizeObserver", ObserverStub);

// jsdom has no `matchMedia`; the toaster asks it for `prefers-reduced-motion` on mount.
if (typeof window !== "undefined" && typeof window.matchMedia !== "function") {
	window.matchMedia = (query: string) => ({
		matches: false,
		media: query,
		onchange: null,
		// `MediaQueryList` still declares the pre-`addEventListener` pair, and library code
		// feature-detects it, so the stand-in has to answer to it as well.
		// oxlint-disable-next-line typescript/no-deprecated -- a polyfill has to implement the interface it stands in for
		addListener: vi.fn(),
		// oxlint-disable-next-line typescript/no-deprecated -- a polyfill has to implement the interface it stands in for
		removeListener: vi.fn(),
		addEventListener: vi.fn(),
		removeEventListener: vi.fn(),
		dispatchEvent: () => false,
	});
}

// jsdom exposes scrollTo but throws when the router restores scroll after navigation.
// Layout and scrolling are covered in browser tests, not this DOM-only environment.
window.scrollTo = () => {
	/* layout is not modelled here */
};

// jsdom has no pointer capture; sonner asks for it on pointerdown to track a toast swipe.
if (typeof Element.prototype.setPointerCapture !== "function") {
	Element.prototype.setPointerCapture = () => {
		/* no pointer to capture */
	};
	Element.prototype.releasePointerCapture = () => {
		/* no pointer to release */
	};
	Element.prototype.hasPointerCapture = () => false;
}

// jsdom has no scrollIntoView either; Base UI calls it to keep the highlighted option in view.
if (typeof Element.prototype.scrollIntoView !== "function") {
	Element.prototype.scrollIntoView = () => {
		/* layout is not modelled here */
	};
}

// jsdom implements no Web Animations API; Base UI's ScrollArea viewport asks its element for
// running animations on a timer, which would otherwise throw *after* a test finished and surface
// as an unhandled error. No assertions depend on animations.
if (typeof Element.prototype.getAnimations !== "function") {
	Element.prototype.getAnimations = () => [];
}
// With `getAnimations` present, Base UI unmounts a closed popup only after an animation frame,
// and jsdom's frames run late when the machine is busy. Its own switch unmounts the popup when it closes.
// Base UI declares the switch in a `global.d.ts` that its package does not export, so it is declared here.
declare global {
	var BASE_UI_ANIMATIONS_DISABLED: boolean;
}
globalThis.BASE_UI_ANIMATIONS_DISABLED = true;

// jsdom has no EventSource; the conversation opens one wherever it shows. A test that drives
// the stream reads `FakeEventSource.latest()`.
if (typeof globalThis.EventSource !== "function") {
	vi.stubGlobal("EventSource", FakeEventSource);
}

// A request that no handler answers fails the test that sent it, unless its file is still listed in
// `unansweredRequestDebt`. `@/mocks/server` answers it with a network error either way.
let currentFile: Readonly<RunnerTestFile> | undefined;
let fileSentUnansweredRequest = false;
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
// A request that an earlier test left in flight belongs to that test, not to this one.
beforeEach(() => {
	unansweredRequests.length = 0;
});
afterEach(({ task }) => {
	server.resetHandlers();
	// Sonner's toast store is a module singleton that outlives a test's render, and a `Toaster`
	// replays every still-active toast to itself on mount — so a toast raised in one test reappears
	// in the next one's DOM and answers its queries. Dismissing marks them all inactive, which is
	// what the replay reads.
	toast.dismiss();
	// Drafts live for the tab; each test is a new tab.
	clearDrafts();
	// Session storage outlives a test too, such as the overview's remembered range: each test starts
	// as a fresh browser tab.
	sessionStorage.clear();
	FakeEventSource.instances = [];
	currentFile = task.file;
	const unanswered = unansweredRequests.splice(0);
	if (unanswered.length === 0) {
		return;
	}
	fileSentUnansweredRequest = true;
	if (!unansweredRequestDebt.has(debtKey(task.file.filepath))) {
		throw new Error(
			`No MSW handler answered (docs/contributor/testing.mdx § Web app requests):\n${unanswered.join("\n")}`,
		);
	}
});
afterAll(() => {
	server.close();
	if (currentFile === undefined) {
		return;
	}
	const key = debtKey(currentFile.filepath);
	if (unansweredRequestDebt.has(key) && !fileSentUnansweredRequest && ranInFull(currentFile)) {
		throw new Error(`${key} answers every request now: remove it from unansweredRequestDebt`);
	}
});

function debtKey(filepath: string): string {
	return path.relative(path.resolve(import.meta.dirname, "../.."), filepath);
}

/** A filtered run, such as `-t`, skips tests that may be the ones still sending a request. */
function ranInFull(task: Readonly<RunnerTask>): boolean {
	return task.type === "test" ? task.mode === "run" : task.tasks.every(ranInFull);
}
