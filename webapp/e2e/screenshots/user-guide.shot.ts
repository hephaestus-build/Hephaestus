/**
 * Captures the user-guide screenshots from the built app, in the light and the dark color scheme.
 * The API is the synthetic workspace in `workspace.ts`; a request that it does not answer fails the
 * capture, so a screen never shows an error state that the guide does not describe.
 */
import { mkdir, stat, writeFile } from "node:fs/promises";
import path from "node:path";

import { type Browser, expect, type Locator, type Page, test } from "@playwright/test";

import { getResponse } from "msw";
import type { WorkspaceMembership } from "@/api/types.gen";

import { BASE_URL } from "./server.ts";
import { handlers, NOW, WORKSPACE } from "./workspace.ts";

const OUTPUT = path.resolve(import.meta.dirname, "../../../docs/static/img/user");
const API = "http://api.test";
const VIEWPORT = { width: 1280, height: 800 };
// WebP of a 1280×800 screen stays well under this; a larger file is a screen full of noise.
const MAX_BYTES = 150_000;

interface Shot {
	/** The user-guide page that shows the image, and the image's name on it. */
	page: string;
	name: string;
	route: string;
	role: WorkspaceMembership["role"];
	ready: (page: Page) => Locator;
}

const shots: Shot[] = [
	{
		page: "practice-profile",
		name: "overview",
		route: `/w/${WORKSPACE}/practice-profile`,
		role: "MEMBER",
		ready: (page) =>
			page.getByRole("heading", { name: "Reviewers cannot see how to check your change" }),
	},
	{
		page: "ai-mentor",
		name: "conversation",
		route: `/w/${WORKSPACE}/mentor`,
		role: "MEMBER",
		ready: (page) => page.getByText("Does a screenshot of the dashboard count?"),
	},
	{
		page: "workspace",
		name: "feedback-mode",
		route: `/w/${WORKSPACE}/admin/practices/review`,
		role: "ADMIN",
		ready: (page) => page.getByText("Approve first").first(),
	},
];

async function open(
	browser: Browser,
	shot: Shot,
	colorScheme: "light" | "dark",
	unanswered: Set<string>,
) {
	const context = await browser.newContext({
		baseURL: BASE_URL,
		viewport: VIEWPORT,
		colorScheme,
		reducedMotion: "reduce",
		locale: "en-US",
		timezoneId: "UTC",
	});
	// The app defaults to dark and keeps the reader's choice under this key.
	await context.addInitScript((scheme) => localStorage.setItem("theme", scheme), colorScheme);
	// Nothing outside the app and its fixture API: no avatar or other picture of a real person.
	await context.route(
		(url) => url.origin !== new URL(BASE_URL).origin && url.origin !== API,
		async (route) => route.abort(),
	);
	await context.route("**/env-config.js", async (route) =>
		route.fulfill({
			contentType: "application/javascript",
			body: `window.__ENV__ = ${JSON.stringify({ APPLICATION_SERVER_URL: API, APPLICATION_CLIENT_URL: BASE_URL })};`,
		}),
	);
	await context.route(`${API}/**`, async (route) => {
		const request = route.request();
		const response = await getResponse(
			handlers(shot.role),
			// A screen only reads, so a request carries no body.
			new Request(request.url(), { method: request.method(), headers: request.headers() }),
		);
		if (response === undefined) {
			unanswered.add(`${request.method()} ${new URL(request.url()).pathname}`);
			return route.fulfill({ status: 404 });
		}
		return route.fulfill({
			status: response.status,
			headers: Object.fromEntries(response.headers),
			body: Buffer.from(await response.arrayBuffer()),
		});
	});
	const page = await context.newPage();
	await page.clock.setFixedTime(NOW);
	return page;
}

/** Playwright writes PNG or JPEG only; Chromium's own capture encodes WebP. */
async function captureWebp(page: Page, file: string): Promise<number> {
	// A focused field blinks its caret, so two captures of the same screen would differ.
	await page.addStyleTag({ content: "* { caret-color: transparent !important; }" });
	const session = await page.context().newCDPSession(page);
	const { data } = await session.send("Page.captureScreenshot", { format: "webp", quality: 90 });
	await mkdir(path.dirname(file), { recursive: true });
	await writeFile(file, Buffer.from(data, "base64"));
	const { size } = await stat(file);
	return size;
}

// oxlint-disable-next-line vitest/prefer-each -- Playwright has no `test.each`; a loop is its idiom.
for (const shot of shots) {
	test.describe(`${shot.page}/${shot.name}`, () => {
		for (const colorScheme of ["light", "dark"] as const) {
			test(colorScheme, async ({ browser }) => {
				const unanswered = new Set<string>();
				const page = await open(browser, shot, colorScheme, unanswered);
				await page.goto(shot.route);
				await page.waitForLoadState("networkidle");
				expect([...unanswered], "requests that workspace.ts does not answer").toEqual([]);
				await expect(shot.ready(page)).toBeVisible();

				const file = path.join(OUTPUT, shot.page, `${shot.name}.${colorScheme}.webp`);
				expect(await captureWebp(page, file)).toBeLessThanOrEqual(MAX_BYTES);
				await page.context().close();
			});
		}
	});
}
