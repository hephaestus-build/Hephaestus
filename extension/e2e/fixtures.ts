import path from "node:path";

import {
	type BrowserContext,
	chromium,
	expect,
	type Frame,
	type Page,
	test as base,
	type Worker,
} from "@playwright/test";
import { z } from "zod";

import {
	gitHubIssue,
	gitHubPullRequest,
	gitHubPullRequestChanges,
	gitHubPullRequestFiles,
	gitHubRepositoryHome,
	gitHubWorkList,
	GITHUB_ORIGIN,
	gitLabMergeRequest,
	GITLAB_ORIGIN,
	gitLabWorkItem,
	gitLabWorkList,
} from "./provider-pages.ts";

// The Chrome APIs the suite reads, inside the extension's own contexts.
declare const chrome: {
	scripting: { getRegisteredContentScripts: () => Promise<unknown[]> };
	tabs: { query: (query: { url: string }) => Promise<{ id?: number }[]> };
	runtime: { sendMessage: (message: unknown) => Promise<unknown> };
	permissions: { remove: (permissions: { origins: string[] }) => Promise<boolean> };
};

export const EXTENSION_ID = "ijkajblcbajjpjbknfgdiiiljipafiko";
const BUNDLE = path.resolve(import.meta.dirname, "../.output/chrome-mv3-e2e");

/** The real Hephaestus server under the `e2e` profile; its API has no `/api` prefix locally. */
export const SERVER_URL = process.env.E2E_SERVER_URL ?? "http://127.0.0.1:8080";
/** Only used for links out; the suite never loads it. */
export const WEB_APP_URL = process.env.E2E_WEB_APP_URL ?? "http://localhost:4200";

async function fulfilProviders(context: BrowserContext): Promise<void> {
	await context.route(`${GITLAB_ORIGIN}/**`, async (route) => {
		const url = new URL(route.request().url());
		let body: string;
		if (url.pathname.endsWith("/-/merge_requests")) {
			body = gitLabWorkList("merge_requests");
		} else if (/\/-\/(?:issues|work_items)$/u.test(url.pathname)) {
			body = gitLabWorkList(url.pathname.endsWith("/work_items") ? "work_items" : "issues");
		} else if (/\/-\/(?:issues|work_items)\//u.test(url.pathname)) {
			body = gitLabWorkItem("Login screen is missing");
		} else {
			body = gitLabMergeRequest(
				"Add the login screen",
				url.pathname.endsWith("/diffs") ? "changes" : "overview",
			);
		}
		await route.fulfill({ contentType: "text/html", body });
	});
	await context.route(`${GITHUB_ORIGIN}/**`, async (route) => {
		const url = new URL(route.request().url());
		let body = gitHubRepositoryHome();
		if (/\/(?:pulls|issues)$/u.test(url.pathname)) {
			body = gitHubWorkList(url.pathname.endsWith("/pulls") ? "pull" : "issues");
		} else if (/\/pull\/\d+\/changes$/u.test(url.pathname)) {
			body = gitHubPullRequestChanges("Fix the flaky test");
		} else if (/\/pull\/\d+\/files$/u.test(url.pathname)) {
			body = gitHubPullRequestFiles("Fix the flaky test");
		} else if (/\/pull\/\d+$/u.test(url.pathname)) {
			body = gitHubPullRequest("Fix the flaky test");
		} else if (/\/issues\/\d+$/u.test(url.pathname)) {
			body = gitHubIssue("The test is flaky");
		}
		await route.fulfill({ contentType: "text/html", body });
	});
}

export const test = base.extend<{ context: BrowserContext; worker: Worker }>({
	// oxlint-disable-next-line no-empty-pattern -- Playwright reads a fixture's dependencies from this pattern; it has none.
	context: async ({}, use) => {
		const context = await chromium.launchPersistentContext("", {
			channel: "chromium",
			args: [`--disable-extensions-except=${BUNDLE}`, `--load-extension=${BUNDLE}`],
		});
		await fulfilProviders(context);
		// The worker registers the provider script for the granted origins as it starts; a page
		// opened before that would never get it.
		const worker = context.serviceWorkers()[0] ?? (await context.waitForEvent("serviceworker"));
		await expect
			.poll(
				async () =>
					worker.evaluate(async () => {
						const scripts = await chrome.scripting.getRegisteredContentScripts();
						return scripts.length;
					}),
				{ timeout: 30_000 },
			)
			.toBe(1);
		await use(context);
		await context.close();
	},
	worker: async ({ context }, use) => {
		const worker = context.serviceWorkers()[0] ?? (await context.waitForEvent("serviceworker"));
		await use(worker);
	},
});

export const OPTIONS_URL = `chrome-extension://${EXTENSION_ID}/options.html`;

/** Connects the extension to the suite's server and signs in through the development door. */
export async function signIn(
	context: BrowserContext,
	username: string,
	{ admin }: { admin: boolean },
): Promise<Page> {
	const options = await context.newPage();
	await options.goto(OPTIONS_URL);
	await options.getByText("Use a self-hosted instance", { exact: true }).click();
	await options.getByLabel("Hephaestus address").fill(SERVER_URL);
	await options.getByLabel(/Web app address/u).fill(WEB_APP_URL);
	await options.getByRole("button", { name: "Connect" }).click();
	await options.getByLabel("User name").fill(username);
	if (admin) {
		await options.getByRole("checkbox", { name: "Instance admin" }).check();
	}
	await options.getByRole("button", { name: "Sign in as developer" }).click();
	await expect(options.getByRole("button", { name: "Sign out" })).toBeVisible();
	return options;
}

/** The id of the tab showing `url`, read inside the worker. */
export async function tabIdOf(worker: Worker, url: string): Promise<number> {
	const id = await worker.evaluate(async (address) => {
		const [tab] = await chrome.tabs.query({ url: address });
		return tab?.id;
	}, url);
	if (id === undefined) {
		throw new Error(`No tab shows ${url}`);
	}
	return id;
}

/** The report's host in the provider page. */
export function reportHost(page: Page) {
	return page.locator("hephaestus-report");
}

function isReportFrame(candidate: Frame): boolean {
	return (
		candidate.url().startsWith("chrome-extension://") &&
		new URL(candidate.url()).pathname === "/inline.html"
	);
}

/** The report's extension frame, once the report is in the page. */
export async function reportFrame(page: Page): Promise<Frame> {
	await expect.poll(() => page.frames().some(isReportFrame)).toBe(true);
	const frame = page.frames().find(isReportFrame);
	if (frame === undefined) {
		throw new Error("The report's frame did not load");
	}
	return frame;
}

/** Opens the report's details in its frame, as the reader does, and returns the frame. */
export async function openInline(page: Page): Promise<Frame> {
	const frame = await reportFrame(page);
	const toggle = frame.getByRole("heading", { level: 2 }).getByRole("button");
	if ((await toggle.getAttribute("aria-expanded")) !== "true") {
		await toggle.click();
	}
	await expect(toggle).toHaveAttribute("aria-expanded", "true");
	return frame;
}

/** The report's box in the page: the host the page lays out, around the frame. */
export async function reportBox(page: Page): Promise<{ top: number; height: number }> {
	return reportHost(page).evaluate((element) => {
		const box = element.getBoundingClientRect();
		return { top: box.top, height: box.height };
	});
}

/** The report's height in the page. */
export async function reportHeight(page: Page): Promise<number> {
	const { height } = await reportBox(page);
	return height;
}

/** Where a provider element is on screen, to prove what the report moves and what it does not. */
export async function boxOf(
	page: Page,
	selector: string,
): Promise<{ top: number; height: number }> {
	return page
		.locator(selector)
		.first()
		.evaluate((element) => {
			const box = element.getBoundingClientRect();
			return { top: box.top, height: box.height };
		});
}

/** Observe only review mutations; navigation and reads must never send one. */
export function watchReviewMutations(context: BrowserContext): string[] {
	const requests: string[] = [];
	context.on("request", (request) => {
		const url = new URL(request.url());
		if (
			url.origin === SERVER_URL &&
			request.method() !== "GET" &&
			/\/(?:review-requests|cancel|delivery\/retry)$/u.test(url.pathname)
		) {
			requests.push(`${request.method()} ${url.pathname}`);
		}
	});
	return requests;
}

/** Sends one RPC from an extension page or frame, as that surface. */
export async function sendAs(
	surface: Page | Frame,
	message: Record<string, unknown>,
): Promise<unknown> {
	return surface.evaluate(async (request) => chrome.runtime.sendMessage(request), message);
}

/** Asks Chrome to remove a host permission from an extension page; the answer or the error. */
export async function removeSite(page: Page, pattern: string): Promise<string> {
	return page.evaluate(async (origin) => {
		try {
			return String(await chrome.permissions.remove({ origins: [origin] }));
		} catch (error) {
			return error instanceof Error ? error.message : "error";
		}
	}, pattern);
}

export interface Hold {
	/** Every response ready to deliver, in completion order. */
	hits: string[];
	/** Delivers the held responses and waits for the intercepted requests to finish. */
	release: () => Promise<void>;
}

/**
 * Holds matching responses after the real server answers. Signing out while they are held must
 * discard the old private data, even though those requests were authorized before revocation.
 */
export async function holdServer(context: BrowserContext, pathPattern: RegExp): Promise<Hold> {
	const gate = Promise.withResolvers<undefined>();
	const hits: string[] = [];
	const deliveries: Promise<undefined>[] = [];
	await context.route(`${SERVER_URL}/**`, async (route) => {
		const { pathname } = new URL(route.request().url());
		if (!pathPattern.test(pathname)) {
			await route.fallback();
			return;
		}
		const delivered = Promise.withResolvers<undefined>();
		deliveries.push(delivered.promise);
		try {
			const response = await route.fetch();
			hits.push(pathname);
			await gate.promise;
			await route.fulfill({ response });
		} catch {
			// The page that asked is gone; nobody is waiting for the answer.
		} finally {
			delivered.resolve(undefined);
		}
	});
	return {
		hits,
		async release() {
			gate.resolve(undefined);
			await Promise.all(deliveries);
		},
	};
}

/** Counts, and passes through, the requests to the server whose path matches. */
export async function watchServer(
	context: BrowserContext,
	pathPattern: RegExp,
): Promise<{ paths: string[]; bodies: unknown[] }> {
	const seen = { paths: [] as string[], bodies: [] as unknown[] };
	await context.route(`${SERVER_URL}/**`, async (route) => {
		const { pathname } = new URL(route.request().url());
		if (!pathPattern.test(pathname)) {
			await route.fallback();
			return;
		}
		const index = seen.paths.push(pathname) - 1;
		const response = await route.fetch();
		seen.bodies[index] = await response.json().catch(() => undefined);
		await route.fulfill({ response });
	});
	return seen;
}

/** Stops the extension's service worker the way Chrome does when it goes idle. */
export async function stopWorker(context: BrowserContext, page: Page): Promise<void> {
	const session = await context.newCDPSession(page);
	const { targetInfos } = await session.send("Target.getTargets");
	const worker = targetInfos.find(
		(target) =>
			target.type === "service_worker" &&
			target.url.startsWith(`chrome-extension://${EXTENSION_ID}/`),
	);
	if (worker === undefined) {
		throw new Error("The extension's service worker is not running");
	}
	await session.send("Target.closeTarget", { targetId: worker.targetId });
	await session.detach();
}

export async function serverAvailable(): Promise<boolean> {
	try {
		const response = await fetch(`${SERVER_URL}/identity-providers`);
		return response.ok;
	} catch {
		return false;
	}
}

const consentStatus = z.object({
	noticeVersion: z.string(),
	researchOrganization: z.string().nullish(),
});

/**
 * Accepts the current notice for a developer the way the web app does, so the suite can show both
 * the consent-required state and what follows it. Dev login answers with the web app's cookie; its
 * value is an ordinary bearer token, which keeps these calls out of the cookie CSRF check.
 */
export async function acceptNotice(username: string, { admin }: { admin: boolean }): Promise<void> {
	const login = await fetch(`${SERVER_URL}/auth/dev-login`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({ username, admin }),
	});
	const token = /HEPHAESTUS_AT=(?<token>[^;]+)/u.exec(login.headers.get("set-cookie") ?? "")?.groups
		?.token;
	if (!login.ok || token === undefined) {
		throw new Error(`Dev login for ${username} failed with ${login.status}`);
	}
	const authorization = { authorization: `Bearer ${token}` };
	const consent = await fetch(`${SERVER_URL}/user/consent`, { headers: authorization });
	const { noticeVersion, researchOrganization } = consentStatus.parse(await consent.json());
	const accepted = await fetch(`${SERVER_URL}/user/consent`, {
		method: "PUT",
		headers: { ...authorization, "content-type": "application/json" },
		body: JSON.stringify({
			noticeVersion,
			termsAccepted: true,
			...(researchOrganization === undefined || researchOrganization === null
				? {}
				: { participateInResearch: false, researchOrganization }),
		}),
	});
	if (!accepted.ok) {
		throw new Error(`Accepting the notice failed with ${accepted.status}`);
	}
}

export { expect } from "@playwright/test";
