import { readFileSync } from "node:fs";
import path from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { MARKER_KEY, RELOAD_WINDOW_MS } from "./preload-error";

const SCRIPT = readFileSync(
	path.join(import.meta.dirname, "../../public/boot-recovery.js"),
	"utf8",
);

/**
 * A page of its own, as the browser gives each load: the script installs its listener on that page's
 * window, and a listener that a case left behind cannot answer the next case's error.
 */
function page() {
	const frame = document.createElement("iframe");
	document.body.append(frame);
	const win = frame.contentWindow;
	if (win === null) {
		throw new Error("the frame has no window");
	}
	const doc = win.document;
	doc.body.innerHTML = '<div id="app"></div>';
	win.sessionStorage.clear();
	const boot = doc.createElement("script");
	boot.textContent = SCRIPT;
	doc.head.append(boot);
	const app = doc.querySelector("#app");
	if (app === null) {
		throw new Error("the page has no #app");
	}
	/** A script of `type` that fails to load, as the browser reports it. */
	const failScript = (type: "module" | "text/javascript") => {
		const script = doc.createElement("script");
		script.type = type;
		doc.body.append(script);
		script.dispatchEvent(new Event("error"));
	};
	/** A modulepreload link that fails to load: the browser reports it before the entry script. */
	const failPreload = () => {
		const link = doc.createElement("link");
		link.rel = "modulepreload";
		doc.head.append(link);
		link.dispatchEvent(new Event("error"));
	};
	return {
		app,
		failPreload,
		failScript,
		marker: () => win.sessionStorage.getItem(MARKER_KEY),
		win,
	};
}

afterEach(() => {
	document.body.replaceChildren();
});

describe("a script the app needs to start fails to load", () => {
	it("reloads the page once, and shows nothing yet", () => {
		const { app, failScript, marker } = page();

		failScript("module");

		expect(marker()).not.toBeNull();
		expect(app.childElementCount).toBe(0);
	});

	it("says the page could not load and offers a reload once a reload did not help", () => {
		const { app, failScript, win } = page();
		// The app's own reload happened near the end of its window, so the script must not reload again.
		// oxlint-disable-next-line no-restricted-properties -- The marker holds the wall-clock time the script reads.
		win.sessionStorage.setItem(MARKER_KEY, String(Date.now() - RELOAD_WINDOW_MS + 5000));

		failScript("module");

		expect(app.querySelector("h1")?.textContent).toBe("We could not load this page");
		expect(app.querySelector("p")?.textContent).toBe(
			"Check your connection, then reload the page.",
		);
		const reload = app.querySelector("button");
		expect(reload?.textContent).toBe("Reload");
		expect(reload?.type).toBe("button");
	});

	it("leaves a page alone once the app has started", () => {
		const { app, failScript, marker } = page();
		app.append(app.ownerDocument.createElement("main"));

		failScript("module");

		expect(marker()).toBeNull();
		expect(app.querySelector("h1")).toBeNull();
	});

	it("waits for the entry script when a preloaded module fails first", () => {
		const { app, failPreload, marker } = page();

		failPreload();

		expect(marker()).toBeNull();
		expect(app.childElementCount).toBe(0);
	});

	it("leaves a failed classic script to the page", () => {
		const { app, failScript, marker } = page();

		failScript("text/javascript");

		expect(marker()).toBeNull();
		expect(app.childElementCount).toBe(0);
	});
});
