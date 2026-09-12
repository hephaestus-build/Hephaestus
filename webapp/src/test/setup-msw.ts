// Global Vitest setup: stand up the MSW Node server so RTL tests of the query-driven auth
// components hit deterministic mocked endpoints (the same handlers Storybook uses).
//
// `onUnhandledRequest: "bypass"` keeps tests that issue no network calls unaffected — only
// the explicitly-mocked auth endpoints are intercepted. Handlers reset after each test so a
// `server.use(...)` override in one test never leaks into the next.

import { toast } from "sonner";
import { afterAll, afterEach, beforeAll } from "vitest";

import { client } from "@/api/client.gen";
import { server } from "@/mocks/server";

// The generated hey-api client resolves request paths against `baseUrl` (it does
// `new URL(path, baseUrl)`); with no baseUrl a relative path like `/user` throws
// "Failed to parse URL". In the app `client.setConfig` runs in `main.tsx`, which the
// test bundle never imports — so configure an absolute base here. The MSW handlers use
// `*/path` wildcards, so this host is matched regardless of its exact value.
client.setConfig({ baseUrl: "http://localhost:8080" });

// jsdom has no ResizeObserver; Base UI's anchor positioning observes elements to keep a popup
// pinned to its trigger. A no-op stub is enough — no assertion depends on the measurements.
if (typeof globalThis.ResizeObserver === "undefined") {
	globalThis.ResizeObserver = class ResizeObserver {
		observe() {}
		unobserve() {}
		disconnect() {}
	};
}

// jsdom has no `matchMedia`; the toaster asks it for `prefers-reduced-motion` on mount.
if (typeof window !== "undefined" && typeof window.matchMedia !== "function") {
	window.matchMedia = (query: string) => ({
		matches: false,
		media: query,
		onchange: null,
		// `MediaQueryList` still declares the pre-`addEventListener` pair, and library code
		// feature-detects it, so the stand-in has to answer to it as well.
		// oxlint-disable-next-line typescript/no-deprecated -- a polyfill has to implement the interface it stands in for
		addListener: () => {},
		// oxlint-disable-next-line typescript/no-deprecated -- a polyfill has to implement the interface it stands in for
		removeListener: () => {},
		addEventListener: () => {},
		removeEventListener: () => {},
		dispatchEvent: () => false,
	});
}

// jsdom exposes scrollTo but throws when the router restores scroll after navigation.
// Layout and scrolling are covered in browser tests, not this DOM-only environment.
window.scrollTo = () => {};

// jsdom has no pointer capture; sonner asks for it on pointerdown to track a toast swipe.
if (typeof Element.prototype.setPointerCapture !== "function") {
	Element.prototype.setPointerCapture = () => {};
	Element.prototype.releasePointerCapture = () => {};
	Element.prototype.hasPointerCapture = () => false;
}

// jsdom has no scrollIntoView either; Base UI calls it to keep the highlighted option in view.
if (typeof Element.prototype.scrollIntoView !== "function") {
	Element.prototype.scrollIntoView = () => {};
}

// jsdom implements no Web Animations API; Base UI's ScrollArea viewport asks its element for
// running animations on a timer, which would otherwise throw *after* a test finished and surface
// as an unhandled error. No assertions depend on animations.
if (typeof Element.prototype.getAnimations !== "function") {
	Element.prototype.getAnimations = () => [];
}

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => {
	server.resetHandlers();
	// Sonner's toast store is a module singleton that outlives a test's render, and a `Toaster`
	// replays every still-active toast to itself on mount — so a toast raised in one test reappears
	// in the next one's DOM and answers its queries. Dismissing marks them all inactive, which is
	// what the replay reads.
	toast.dismiss();
});
afterAll(() => server.close());
