import type { ConsoleMessage, Page } from "@playwright/test";

import { MAX_FRAME_HEIGHT } from "~/shared/frame-messages";
import { required } from "~/testing/required";

import {
	boxOf,
	expect,
	openInline,
	reportHeight,
	reportFrame,
	reportHost,
	tabIdOf,
	test,
} from "./fixtures.ts";
import {
	GITHUB_ISSUE,
	GITHUB_ORIGIN,
	GITHUB_PR,
	GITLAB_ISSUE,
	GITLAB_MR,
	GITLAB_ORIGIN,
	gitLabMergeRequest,
} from "./provider-pages.ts";

/*
 * What these tests do inside the provider page. Playwright sends each function's source into the
 * page, so each holds everything it needs.
 */

/** Announces a tab switch, then settles the panes' CSS as the provider does, a moment later. */
function switchPanes(tab: "changes" | "overview"): void {
	window.dispatchEvent(new CustomEvent("fixture:tab", { detail: tab }));
	for (const [id, visible] of [
		["diffs", tab === "changes"],
		["overview-pane", tab === "overview"],
	] as const) {
		const pane = document.getElementById(id);
		if (pane === null) {
			throw new Error("Missing tab pane");
		}
		pane.hidden = false;
		pane.style.contentVisibility = visible ? "visible" : "hidden";
	}
}

/** Replaces the merge request's discussion with a copy of itself, report left out. */
function rerenderDiscussion(): void {
	const discussion = document.querySelector(".issuable-discussion");
	const copy = discussion?.cloneNode(true);
	if (copy instanceof Element) {
		copy.querySelector("hephaestus-report")?.remove();
		discussion?.replaceWith(copy);
	}
}

/** Posts a forged resize to the page, and says whether the page can reach an extension API. */
function forgeResize(nonce: string | null): string {
	window.postMessage({ type: "hephaestus:size", open: nonce, height: 3999 }, "*");
	const chromeGlobal: unknown = Reflect.get(window, "chrome");
	return typeof chromeGlobal === "object" && chromeGlobal !== null
		? typeof Reflect.get(chromeGlobal, "runtime")
		: "undefined";
}

/** Everything the page can read of the report's host. */
function shadowText(host: Element): string {
	return host.shadowRoot?.textContent.replaceAll(/\s+/gu, " ").trim() ?? "";
}

/** Rewrites the next report frame's address to ask for it opened. */
function askForExpansionInFrameAddress(): void {
	const observer = new MutationObserver(() => {
		const iframe = document.querySelector("hephaestus-report")?.shadowRoot?.querySelector("iframe");
		if (iframe !== null && iframe !== undefined && !iframe.src.includes("expanded")) {
			iframe.src = `${iframe.src}&expanded=1`;
			observer.disconnect();
		}
	});
	observer.observe(document.body, { childList: true, subtree: true });
}

/** Replaces the report frame's document as soon as it is inserted. */
function replaceFrameDocumentOnInsert(): void {
	const observer = new MutationObserver(() => {
		const frame = document.querySelector("hephaestus-report")?.shadowRoot?.querySelector("iframe");
		if (frame !== null && frame !== undefined) {
			frame.setAttribute("srcdoc", "");
			observer.disconnect();
		}
	});
	document.addEventListener("DOMContentLoaded", () => {
		observer.observe(document.body, { childList: true, subtree: true });
	});
}

/** Takes the host or its parent out and back in one turn, and switches the theme meanwhile. */
function reinsertInOneTurn(host: Element, kind: "host" | "ancestor"): void {
	const node = kind === "host" ? host : host.parentElement;
	const parent = node?.parentElement;
	if (node === null || parent === null || parent === undefined) {
		throw new Error("Missing report ancestor");
	}
	const next = node.nextSibling;
	node.remove();
	if (next === null) {
		parent.append(node);
	} else {
		next.before(node);
	}
	document.documentElement.dataset.colorMode = "dark";
	document.documentElement.dataset.darkTheme = "dark_dimmed";
}

/** A tall page with one more provider link after the report, to tab to. */
function appendNextProviderAction(): void {
	document.body.style.minHeight = "6000px";
	const activity = document.querySelector(
		'.issuable-discussion > #notes, [data-partial-name="pullRequestsConversationsRoute.Timeline"]',
	);
	if (activity === null) {
		throw new Error("Missing provider activity");
	}
	const next = document.createElement("a");
	next.href = "#after-report";
	next.textContent = "Next provider action";
	activity.append(next);
}

/** The scheme a page element is drawn with, as its computed `color-scheme` says. */
function usedColorScheme(element: Element): string {
	return getComputedStyle(element).colorScheme;
}

/**
 * Makes the fixture page follow the system scheme, as GitHub does when set to sync with it, declaring
 * `declared` as its own `color-scheme` — or nothing, the default.
 */
function followSystemScheme(declared: string): void {
	document.documentElement.dataset.colorMode = "auto";
	document.documentElement.dataset.lightTheme = "light";
	document.documentElement.dataset.darkTheme = "dark";
	document.documentElement.style.colorScheme = declared;
	const dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
	for (const row of document.querySelectorAll("li")) {
		row.style.background = dark ? "#151b23" : "#f6f8fa";
	}
}

/** Notes, inside the preview's own document, that a click arrived there. */
function listenForClicks(): void {
	Reflect.set(window, "fixtureClicked", false);
	document.addEventListener("click", () => {
		Reflect.set(window, "fixtureClicked", true);
	});
}

function wasClicked(): boolean {
	return Reflect.get(window, "fixtureClicked") === true;
}

/** Collects the page's console messages about `postMessage`, which a lost frame would log. */
function collectPostMessageFailures(failures: string[]) {
	return (message: ConsoleMessage) => {
		if (message.text().includes("postMessage")) {
			failures.push(message.text());
		}
	};
}

/**
 * The real extension bundle on fixture provider pages, with no Hephaestus server: every report here
 * says the extension is not connected, which is enough to prove where it goes, what the page can
 * see of it, and how it follows the provider.
 */

/** The collapsed report: the 48px line plus the host's 1px border above and below. */
const COLLAPSED = 50;

/** A list row's preview: one line of the row, with no border of its own. */
const STRIP = 32;

/** The report host's place among the provider's own elements, by their own names. */
async function neighbours(page: Page) {
	return reportHost(page).evaluate((host) => {
		// oxlint-disable-next-line unicorn/consistent-function-scoping -- Playwright serialises this callback into the page, so it must hold its own helpers.
		const label = (element: Element | null): string | null => {
			if (!(element instanceof HTMLElement)) {
				return null;
			}
			const names = [
				element.id,
				element.className,
				element.dataset.partialName,
				element.dataset.testid,
			];
			return names.find((name) => name !== undefined && name !== "") ?? null;
		};
		return {
			parent: label(host.parentElement),
			previous: label(host.previousElementSibling),
			next: label(host.nextElementSibling),
		};
	});
}

async function nextToReport(page: Page): Promise<string | null> {
	const { next } = await neighbours(page);
	return next;
}

async function frameToken(page: Page): Promise<string | null> {
	const frame = await reportFrame(page);
	return new URL(frame.url()).searchParams.get("open");
}

async function showTab(page: Page, tab: "overview" | "changes"): Promise<void> {
	await page.evaluate((name) => {
		window.dispatchEvent(new CustomEvent("fixture:tab", { detail: name }));
	}, tab);
}

async function settled(page: Page): Promise<void> {
	await page.evaluate(async () => {
		const done = Promise.withResolvers<undefined>();
		requestAnimationFrame(() => {
			requestAnimationFrame(() => done.resolve(undefined));
		});
		await done.promise;
	});
}

test.describe("where the report goes", () => {
	test("in a GitLab merge request's reports area, before its activity", async ({ context }) => {
		const page = await context.newPage();
		await page.goto(GITLAB_MR);
		await expect(reportHost(page)).toHaveCount(1);
		expect(await neighbours(page)).toStrictEqual({
			parent: "issuable-discussion",
			previous: "widget-state",
			next: "notes",
		});
		const frame = await reportFrame(page);
		await expect(frame.getByRole("heading", { level: 2 })).toContainText(
			"Connect the extension to Hephaestus",
		);
		await expect.poll(async () => reportHeight(page)).toBe(COLLAPSED);
	});

	test("keeps the GitLab report in Overview while inactive Rapid Diffs uses content-visibility hidden", async ({
		context,
	}) => {
		await context.route(GITLAB_MR, async (route) => {
			const body = gitLabMergeRequest("Add the login screen").replace(
				'class="tab-pane diffs" hidden',
				'class="tab-pane diffs" style="display:block;visibility:visible;content-visibility:hidden"',
			);
			await route.fulfill({ contentType: "text/html", body });
		});
		const page = await context.newPage();
		await page.goto(GITLAB_MR);
		const computed = await page.locator("#diffs").evaluate((pane) => {
			const style = getComputedStyle(pane);
			return {
				display: style.display,
				visibility: style.visibility,
				contentVisibility: style.contentVisibility,
			};
		});
		expect(computed).toStrictEqual({
			display: "block",
			visibility: "visible",
			contentVisibility: "hidden",
		});
		await expect(reportHost(page)).toHaveCount(1);
		await expect.poll(async () => nextToReport(page)).toBe("notes");
		const frame = await openInline(page);
		await expect(frame.getByText(/Connect the extension to Hephaestus and sign in/u)).toBeVisible();
		await expect(page.locator("#diffs").locator("hephaestus-report")).toHaveCount(0);
		for (const [showing, next] of [
			["changes", "rd-app-diffs-list"],
			["overview", "notes"],
		] as const) {
			await page.evaluate(switchPanes, showing);
			await expect.poll(async () => nextToReport(page)).toBe(next);
			await expect(reportHost(page)).toHaveCount(1);
			const current = await reportFrame(page);
			await expect(current.getByRole("heading", { level: 2 })).toBeVisible();
		}
	});

	test("between a GitHub pull request's description and its timeline", async ({ context }) => {
		const page = await context.newPage();
		await page.goto(GITHUB_PR);
		await expect(reportHost(page)).toHaveCount(1);
		expect(await neighbours(page)).toStrictEqual({
			parent: "js-discussion",
			previous: "pullRequestsConversationsRoute.Body",
			next: "pullRequestsConversationsRoute.Timeline",
		});
	});

	test("between a GitHub issue's description and its activity", async ({ context }) => {
		const page = await context.newPage();
		await page.goto(GITHUB_ISSUE);
		await expect(reportHost(page)).toHaveCount(1);
		const place = await neighbours(page);
		expect(place.previous).toBe("issue-viewer-issue-container");
		expect(place.next).toBe("react-comments-container");
	});

	test("first in a GitLab work item's activity area, adding no grid area", async ({ context }) => {
		const page = await context.newPage();
		await page.goto(GITLAB_ISSUE);
		await expect(reportHost(page)).toHaveCount(1);
		const place = await neighbours(page);
		expect(place.parent).toBe("detail-layout-activity");
		expect(place.previous).toBeNull();
		expect(place.next).toBe("work-item-notes");
		const gridChildren = await page.locator('[data-testid="detail-layout-container"] > *').count();
		expect(gridChildren).toBe(4);
	});

	test("above the changed files on GitHub and GitLab", async ({ context }) => {
		const page = await context.newPage();
		await page.goto(`${GITHUB_PR}/changes`);
		await expect(reportHost(page)).toHaveCount(1);
		expect(await nextToReport(page)).toBe("progressive-diffs-list");
		// The server-rendered files view GitHub still serves, e.g. signed out.
		await page.goto(`${GITHUB_PR}/files`);
		await expect(reportHost(page)).toHaveCount(1);
		expect(await neighbours(page)).toMatchObject({
			parent: "files",
			next: "js-diff-progressive-container",
		});
		await page.goto(`${GITLAB_MR}/diffs`);
		await expect(reportHost(page)).toHaveCount(1);
		expect(await neighbours(page)).toMatchObject({
			parent: "rd-app-content",
			next: "rd-app-diffs-list",
		});
	});

	test("nowhere on a page with no slot, rather than floating over it", async ({ context }) => {
		const page = await context.newPage();
		for (const url of [
			`${GITHUB_ORIGIN}/hephaestus-build/demo`,
			`${GITHUB_PR}/commits`,
			`${GITLAB_ORIGIN}/groups/ext/-/work_items/7`,
		]) {
			await page.goto(url);
			await settled(page);
			await expect(reportHost(page), url).toHaveCount(0);
		}
	});
});

test.describe("in the page's flow", () => {
	test("opens in place, moving the activity below it, and closes back to its line", async ({
		context,
	}) => {
		const page = await context.newPage();
		await page.goto(GITLAB_MR);
		await reportFrame(page);
		await expect.poll(async () => reportHeight(page)).toBe(COLLAPSED);
		const activity = await boxOf(page, "#first-note");
		const frame = await openInline(page);
		await expect(frame.getByText(/Connect the extension to Hephaestus and sign in/u)).toBeVisible();
		await expect.poll(async () => reportHeight(page)).toBeGreaterThan(COLLAPSED);
		const opened = await boxOf(page, "#first-note");
		expect(opened.top).toBeGreaterThan(activity.top);
		// No scrolling inside the report: the frame is exactly as tall as what it shows.
		const scrolls = await frame.evaluate(
			() => document.documentElement.scrollHeight > window.innerHeight + 1,
		);
		expect(scrolls).toBe(false);
		await frame.getByRole("heading", { level: 2 }).getByRole("button").click();
		await expect.poll(async () => reportHeight(page)).toBe(COLLAPSED);
	});

	test("follows the reader between the overview and the changes, one report at a time", async ({
		context,
	}) => {
		const page = await context.newPage();
		await page.goto(GITLAB_MR);
		await reportFrame(page);
		await showTab(page, "changes");
		await expect.poll(async () => nextToReport(page)).toBe("rd-app-diffs-list");
		await expect(reportHost(page)).toHaveCount(1);
		await showTab(page, "overview");
		await expect.poll(async () => nextToReport(page)).toBe("notes");
		await expect(reportHost(page)).toHaveCount(1);
	});

	test("follows the provider's navigation to other work and away from work", async ({
		context,
	}) => {
		const page = await context.newPage();
		await page.goto(GITLAB_MR);
		const first = await frameToken(page);
		await page.evaluate(() => {
			history.pushState({}, "", "/ext/demo/-/merge_requests/9");
			document.body.append(document.createElement("div"));
		});
		await expect
			.poll(async () =>
				reportHost(page)
					.locator("iframe")
					.evaluate((iframe: HTMLIFrameElement) => new URL(iframe.src).searchParams.get("open")),
			)
			.not.toBe(first);
		await page.evaluate(() => {
			history.pushState({}, "", "/ext/demo");
		});
		await expect(reportHost(page)).toHaveCount(0);
	});

	test("keeps exactly one report when the provider re-renders its content", async ({ context }) => {
		const page = await context.newPage();
		await page.goto(GITLAB_MR);
		await reportFrame(page);
		await page.evaluate(rerenderDiscussion);
		await expect(page.locator(".issuable-discussion hephaestus-report")).toHaveCount(1);
		await settled(page);
		await expect(reportHost(page)).toHaveCount(1);
	});
});

test.describe("what the page can see and do", () => {
	test("reads nothing about the work or the account, and cannot resize the frame", async ({
		context,
	}) => {
		const page = await context.newPage();
		await page.goto(GITLAB_MR);
		const frame = await reportFrame(page);
		await expect(frame.getByRole("heading", { level: 2 })).toContainText("Connect the extension");
		await expect.poll(async () => reportHeight(page)).toBe(COLLAPSED);
		const iframe = reportHost(page).locator("iframe");
		const open = new URL(
			required(await iframe.getAttribute("src"), "the frame's address"),
		).searchParams.get("open");
		const exposed = await page.evaluate(forgeResize, open);
		expect(exposed).toBe("undefined");
		await settled(page);
		expect(await reportHeight(page)).toBe(COLLAPSED);
		await expect(page.locator("body")).not.toContainText("Connect the extension");
		const hostText = await reportHost(page).evaluate(shadowText);
		expect(hostText).not.toContain("Connect");
	});

	test("cannot open the report from the address it gives the frame", async ({ context }) => {
		const page = await context.newPage();
		await page.goto(GITLAB_MR);
		await page.evaluate(askForExpansionInFrameAddress);
		await page.evaluate(() => {
			document.querySelector("hephaestus-report")?.remove();
		});
		const frame = await reportFrame(page);
		await expect(frame.getByRole("heading", { level: 2 }).getByRole("button")).toHaveAttribute(
			"aria-expanded",
			"false",
		);
		await expect.poll(async () => reportHeight(page)).toBe(COLLAPSED);
	});

	test("shows a generic failure when the page replaces the frame document, without fighting it", async ({
		context,
	}) => {
		const page = await context.newPage();
		// A hostile or incompatible page controls its DOM, and the report's shadow root is open to it.
		// Set srcdoc at insertion, before the extension document can report ready; do not weaken
		// browser protections to make it load.
		await page.addInitScript(replaceFrameDocumentOnInsert);
		await page.goto(GITLAB_MR);
		await expect(reportHost(page)).toHaveCount(1);
		await expect(page.getByText(/Could not load here/u)).toBeVisible({ timeout: 20_000 });
		await expect.poll(async () => reportHeight(page)).toBe(COLLAPSED);
	});
});

test.describe("a report moved by the page", () => {
	for (const removed of ["host", "ancestor"] as const) {
		test(`recovers the frame document after its ${removed} is removed and reinserted in one turn`, async ({
			context,
		}) => {
			const page = await context.newPage();
			const failures: string[] = [];
			page.on("console", collectPostMessageFailures(failures));
			await page.emulateMedia({ colorScheme: "light" });
			await page.goto(GITHUB_PR);
			const first = await reportFrame(page);
			await expect(first.getByRole("heading", { level: 2 })).toBeVisible();
			await expect(first.locator("html")).toHaveAttribute("data-palette", "light");
			await reportHost(page).evaluate(reinsertInOneTurn, removed);
			await settled(page);
			expect(failures).toStrictEqual([]);
			await expect
				.poll(async () => {
					const frame = await reportFrame(page);
					return frame.evaluate(() => document.documentElement.dataset.palette);
				})
				.toBe("dark_dimmed");
			const current = await reportFrame(page);
			await expect(current.getByRole("heading", { level: 2 })).toBeVisible();
			await expect(reportHost(page)).toHaveCount(1);
			expect(failures).toStrictEqual([]);
		});
	}
});

test.describe("scrolling across the report", () => {
	for (const url of [GITHUB_PR, GITLAB_MR]) {
		test(`continues scrolling the provider over collapsed and expanded reports on ${new URL(url).host}`, async ({
			context,
		}) => {
			const page = await context.newPage();
			await page.setViewportSize({ width: 1000, height: 800 });
			await page.goto(url);
			await page.evaluate(() => {
				document.body.style.minHeight = "6000px";
			});
			for (const show of [reportFrame, openInline]) {
				await page.evaluate(() => window.scrollTo(0, 0));
				const frame = await show(page);
				await expect(frame.getByRole("heading", { level: 2 })).toBeVisible();
				await expect
					.poll(async () =>
						frame.evaluate(() => document.documentElement.scrollHeight - window.innerHeight),
					)
					.toBeLessThanOrEqual(1);
				const box = required(await reportHost(page).boundingBox(), "report bounds");
				await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
				await page.mouse.wheel(0, 300);
				await expect.poll(async () => page.evaluate(() => window.scrollY)).toBeGreaterThan(100);
			}
		});
	}

	test("scrolls a height-capped report internally then continues into the provider at its boundary", async ({
		context,
	}) => {
		const page = await context.newPage();
		await page.setViewportSize({ width: 1000, height: 800 });
		await page.goto(GITLAB_MR);
		await page.evaluate(() => {
			document.body.style.minHeight = "8000px";
		});
		const frame = await openInline(page);
		// Geometry-only long-report fixture: use the real root observer and size bridge, without data.
		await frame.locator("#root > div").evaluate((root, maximum) => {
			const content = document.createElement("div");
			content.style.height = `${maximum + 1000}px`;
			content.textContent = "Long report geometry fixture";
			root.append(content);
		}, MAX_FRAME_HEIGHT);
		await expect.poll(async () => frame.evaluate(() => window.innerHeight)).toBe(MAX_FRAME_HEIGHT);
		const box = required(await reportHost(page).boundingBox(), "report bounds");
		await page.mouse.move(box.x + box.width / 2, box.y + 100);
		await page.mouse.wheel(0, 300);
		await expect.poll(async () => frame.evaluate(() => window.scrollY)).toBeGreaterThan(100);
		expect(await page.evaluate(() => window.scrollY)).toBe(0);
		await frame.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
		await page.mouse.wheel(0, 300);
		await expect.poll(async () => page.evaluate(() => window.scrollY)).toBeGreaterThan(100);
	});
});

test.describe("the report's look", () => {
	test("adopts GitLab's dark theme when the frame becomes ready", async ({ context }) => {
		const page = await context.newPage();
		await page.emulateMedia({ colorScheme: "light" });
		await page.goto(GITLAB_MR);
		await page.evaluate(() => document.documentElement.classList.add("gl-dark"));
		const frame = await reportFrame(page);
		await expect(frame.locator("html")).toHaveClass(/\bdark\b/u);
		await expect(reportHost(page)).toHaveAttribute("data-theme", "dark");
	});

	test("follows GitHub's selected palettes and the system without replacing the frame", async ({
		context,
	}) => {
		const page = await context.newPage();
		await page.emulateMedia({ colorScheme: "light" });
		await page.goto(GITHUB_PR);
		await page.evaluate(() => {
			document.documentElement.dataset.colorMode = "dark";
			document.documentElement.dataset.darkTheme = "dark_dimmed";
			document.documentElement.dataset.lightTheme = "light_high_contrast";
		});
		const frame = await reportFrame(page);
		await expect(frame.locator("html")).toHaveAttribute("data-palette", "dark_dimmed");
		const iframe = reportHost(page).locator("iframe");
		const original = await iframe.getAttribute("src");
		await page.evaluate(() => {
			document.documentElement.dataset.darkTheme = "dark_high_contrast";
		});
		await expect(frame.locator("html")).toHaveAttribute("data-palette", "dark_high_contrast");
		await page.evaluate(() => {
			document.documentElement.dataset.colorMode = "auto";
		});
		await expect(frame.locator("html")).toHaveAttribute("data-palette", "light_high_contrast");
		await page.emulateMedia({ colorScheme: "dark" });
		await expect(frame.locator("html")).toHaveAttribute("data-palette", "dark_high_contrast");
		await expect(iframe).toHaveAttribute("src", required(original, "the frame's address"));
	});

	for (const url of [GITHUB_PR, GITLAB_MR]) {
		test(`keeps one setup action and keyboard and wheel access at 320px on ${new URL(url).host}`, async ({
			context,
		}) => {
			const page = await context.newPage();
			await page.setViewportSize({ width: 320, height: 700 });
			await page.goto(url);
			await page.evaluate(appendNextProviderAction);
			const frame = await reportFrame(page);
			const action = frame.getByRole("button", { name: "Set up", exact: true });
			const disclosure = frame.getByRole("heading", { level: 2 }).getByRole("button");
			await expect(action).toHaveCount(1);
			await expect(action).toBeVisible();
			await expect(disclosure).toHaveAttribute("aria-expanded", "false");
			await disclosure.focus();
			await page.keyboard.press("Enter");
			await expect(disclosure).toHaveAttribute("aria-expanded", "true");
			await expect(action).toHaveCount(1);
			await page.keyboard.press("Tab");
			await expect(action).toBeFocused();
			await page.keyboard.press("Tab");
			await expect(page.getByRole("link", { name: "Next provider action" })).toBeFocused();
			expect(
				await frame.evaluate(
					() => document.documentElement.scrollWidth - document.documentElement.clientWidth,
				),
			).toBeLessThanOrEqual(1);
			await page.evaluate(() => window.scrollTo(0, 0));
			const bounds = required(await reportHost(page).boundingBox(), "report bounds");
			await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
			await page.mouse.wheel(0, 300);
			await expect.poll(async () => page.evaluate(() => window.scrollY)).toBeGreaterThan(100);
		});
	}

	test("keeps its line whole in a narrow window", async ({ context }) => {
		const page = await context.newPage();
		await page.setViewportSize({ width: 360, height: 700 });
		await page.goto(GITHUB_PR);
		const frame = await reportFrame(page);
		await expect.poll(async () => reportHeight(page)).toBe(COLLAPSED);
		const overflows = await frame.evaluate(
			() => document.documentElement.scrollWidth > document.documentElement.clientWidth,
		);
		expect(overflows).toBe(false);
	});
});

// These exercise the browser's isolated content world and WXT invalidation. The fixture build's
// required grants cannot be revoked; optional-permission transitions themselves have unit coverage.
declare const chrome: {
	scripting: {
		executeScript: (details: {
			target: { tabId: number; frameIds: number[] };
			files: string[];
		}) => Promise<unknown>;
	};
	tabs: { sendMessage: (tabId: number, message: unknown) => Promise<unknown> };
};

test("reinjection replaces the old report and access removal tears down the current one", async ({
	context,
	worker,
}) => {
	const page = await context.newPage();
	await page.goto(GITLAB_MR);
	await expect(reportHost(page)).toHaveCount(1);
	const tabId = await tabIdOf(worker, GITLAB_MR);
	const inject = async () =>
		worker.evaluate(async (id) => {
			await chrome.scripting.executeScript({
				target: { tabId: id, frameIds: [0] },
				files: ["content-scripts/provider.js"],
			});
		}, tabId);
	await inject();
	await settled(page);
	await expect(reportHost(page)).toHaveCount(1);
	await worker.evaluate(async (id) => {
		await chrome.tabs.sendMessage(id, { type: "hephaestus:provider-access", enabled: false });
	}, tabId);
	await expect(reportHost(page)).toHaveCount(0);
	await page.evaluate(() => {
		history.pushState({}, "", "/ext/demo/-/merge_requests/9");
		document.body.append(document.createElement("div"));
	});
	await settled(page);
	await expect(reportHost(page)).toHaveCount(0);
	await inject();
	await expect(reportHost(page)).toHaveCount(1);
});

test.describe("a list row's preview in the row", () => {
	for (const [scheme, declared] of [
		["light", ""],
		["light", "light dark"],
		["dark", "light dark"],
	] as const) {
		test(`shows the row's own background through it, not an opaque ${scheme} box, on a page declaring "${declared}"`, async ({
			context,
		}) => {
			const page = await context.newPage();
			await page.emulateMedia({ colorScheme: scheme });
			await page.goto(`${GITHUB_ORIGIN}/hephaestus-build/demo/pulls`);
			await page.evaluate(followSystemScheme, declared);
			await page.locator("hephaestus-inspect").first().getByRole("button").click();
			const frame = await reportFrame(page);
			await expect(frame.getByRole("status")).toContainText("Connect the extension");
			// An empty stretch of the preview looks exactly like an empty stretch of the same row.
			const strip = required(await reportHost(page).boundingBox(), "preview bounds");
			const row = required(await page.locator("li").first().boundingBox(), "row bounds");
			const size = { width: 6, height: 6 };
			await expect
				.poll(async () => {
					const insidePreview = await page.screenshot({
						clip: { x: strip.x + strip.width - 12, y: strip.y + 4, ...size },
					});
					const insideRow = await page.screenshot({
						clip: { x: row.x + 2, y: strip.y + 4, ...size },
					});
					return insidePreview.equals(insideRow);
				})
				.toBe(true);
			// Because the embedding iframe and its document use one explicit scheme, the browser keeps
			// the frame's canvas transparent instead of painting an opaque one.
			const iframe = reportHost(page).locator("iframe");
			await expect.poll(async () => iframe.evaluate(usedColorScheme)).toBe(scheme);
			await expect
				.poll(async () =>
					frame.evaluate(() => getComputedStyle(document.documentElement).colorScheme),
				)
				.toBe(scheme);
		});
	}
});

test.describe("a repository's list", () => {
	for (const list of [
		{ url: `${GITHUB_ORIGIN}/hephaestus-build/demo/pulls`, number: 7 },
		{ url: `${GITHUB_ORIGIN}/hephaestus-build/demo/issues`, number: 7 },
		{ url: `${GITLAB_ORIGIN}/ext/demo/-/merge_requests`, number: 4 },
		{ url: `${GITLAB_ORIGIN}/ext/demo/-/work_items`, number: 4 },
	]) {
		test(`previews one work at a time in ${list.url}`, async ({ context }) => {
			const page = await context.newPage();
			await page.goto(list.url);
			const inspect = page.locator("hephaestus-inspect");
			await expect(inspect).toHaveCount(2);
			await expect(page.locator("h3 hephaestus-inspect")).toHaveCount(0);
			await expect(reportHost(page)).toHaveCount(0);
			await inspect.first().getByRole("button").click();
			await expect(reportHost(page)).toHaveCount(1);
			const firstFrame = await reportFrame(page);
			expect(new URL(firstFrame.url()).searchParams.get("work")).toMatch(
				new RegExp(`/${list.number}$`, "u"),
			);
			// One line inside the row, filled at once: no card, nothing to open, one height.
			await expect(firstFrame.getByRole("status")).toContainText(
				"Connect the extension to Hephaestus",
			);
			await expect(firstFrame.locator("[aria-expanded]")).toHaveCount(0);
			await expect.poll(async () => reportHeight(page)).toBe(STRIP);
			expect(
				await reportHost(page).evaluate(
					(host) => host.closest('li, [data-testid="issuable-container"]') !== null,
				),
			).toBe(true);
			// Lined up with the row's title, not with the row's edge or its checkbox.
			const title = required(
				await page
					.locator('[data-testid$="title-link"]')
					.filter({ hasText: `Work ${list.number}` })
					.first()
					.boundingBox(),
				"title bounds",
			);
			const strip = required(await reportHost(page).boundingBox(), "preview bounds");
			expect(Math.abs(strip.x - title.x)).toBeLessThanOrEqual(2);
			// A real pointer lands in the preview itself: GitLab's full-row link does not take it, and
			// the page does not navigate.
			await firstFrame.evaluate(listenForClicks);
			await firstFrame.getByRole("status").click({ timeout: 5000 });
			await expect.poll(async () => firstFrame.evaluate(wasClicked)).toBe(true);
			await expect(page).toHaveURL(list.url);
			await expect.poll(async () => reportHeight(page)).toBe(STRIP);
			// The page scrolls natively over the preview.
			await page.evaluate(() => {
				document.body.style.minHeight = "4000px";
			});
			const bounds = required(await reportHost(page).boundingBox(), "preview bounds");
			await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
			await page.mouse.wheel(0, 300);
			await expect.poll(async () => page.evaluate(() => window.scrollY)).toBeGreaterThan(100);
			await page.evaluate(() => window.scrollTo(0, 0));

			await inspect.last().getByRole("button").click();
			await expect(reportHost(page)).toHaveCount(1);
			await expect
				.poll(async () => {
					const current = await reportFrame(page);
					return new URL(current.url()).searchParams.get("work");
				})
				.toMatch(new RegExp(`/${list.number + 1}$`, "u"));
			await expect(inspect.first().getByRole("button")).toHaveAttribute("aria-expanded", "false");
			await expect(inspect.last().getByRole("button")).toHaveAttribute("aria-expanded", "true");
			await inspect.last().getByRole("button").click();
			await expect(reportHost(page)).toHaveCount(0);
			const first = inspect.first().getByRole("button");
			await first.focus();
			await page.keyboard.press("Enter");
			await expect(reportHost(page)).toHaveCount(1);
			await expect(page).toHaveURL(list.url);
			await page.evaluate(() => {
				history.pushState({}, "", `${location.pathname}?page=2`);
			});
			await expect(reportHost(page)).toHaveCount(0);
		});
	}
});
