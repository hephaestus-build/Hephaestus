import type { Frame, Locator, Page, Request } from "@playwright/test";

import { required } from "~/testing/required";

import {
	acceptNotice,
	expect,
	holdServer,
	openInline,
	OPTIONS_URL,
	reportFrame,
	removeSite,
	sendAs,
	SERVER_URL,
	serverAvailable,
	signIn,
	stopWorker,
	tabIdOf,
	test,
	watchReviewMutations,
	WEB_APP_URL,
} from "./fixtures.ts";
import { GITLAB_ISSUE, GITLAB_MR, GITLAB_MR5, GITLAB_MR6 } from "./provider-pages.ts";

/**
 * Real Hephaestus APIs and authentication, fixture provider pages. This does not prove provider
 * ingestion, a review execution, production OAuth, or the web app's authoritative actions.
 */
const QUEUED_JOB = "7d5e0c3a-1b2c-4d3e-8f40-000000000001";
const FAILED_JOB = "7d5e0c3a-1b2c-4d3e-8f40-000000000002";
const MR4_WORK_ID = "920010";
const TRACE_URL = `${WEB_APP_URL}/w/ext-e2e/reviews/scm.pull_request/${MR4_WORK_ID}`;

async function expectSafeLink(link: Locator, href: string): Promise<void> {
	await expect(link).toHaveAttribute("href", href);
	await expect(link).toHaveAttribute("target", "_blank");
	await expect(link).toHaveAttribute("rel", /\bnoopener\b/u);
}

async function providerTab(page: Page, url: string): Promise<void> {
	await page.goto(url);
	await expect(page.locator("hephaestus-report")).toHaveCount(1);
}

/** The report's line, which names its state. */
function line(frame: Frame): Locator {
	return frame.getByRole("heading", { level: 2 });
}

/** The report shows this work: its link into Hephaestus is the work's own page. */
async function expectWork(frame: Frame, trace: string): Promise<void> {
	await expectSafeLink(frame.getByRole("link", { name: /^Open in Hephaestus/u }).last(), trace);
}

/** The same loopback server under its other name: a distinct instance to the extension. */
function otherLoopback(hostname: string): string {
	return hostname === "localhost" ? "127.0.0.1" : "localhost";
}

/** Records every request to the server's practice endpoints. */
function recordPracticeReads(reads: string[]) {
	return (request: Request) => {
		if (request.url().startsWith(SERVER_URL) && request.url().includes("/practices/")) {
			reads.push(request.url());
		}
	};
}

const MR5_TRACE = `${WEB_APP_URL}/w/ext-e2e/reviews/scm.pull_request/920012`;

test.beforeAll(async () => {
	if (!(await serverAvailable())) {
		throw new Error(`No Hephaestus server answers at ${SERVER_URL}; the server project needs one.`);
	}
});

test.describe("signing in", () => {
	test("asks a developer who has not accepted the notice to do so first", async ({ context }) => {
		await signIn(context, `e2e-notice-${Date.now()}`, { admin: false });
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR);
		const frame = await openInline(page);
		await expect(line(frame)).toContainText("One step left in Hephaestus");
	});

	test("tells same-number work apart and survives a worker restart", async ({ context }) => {
		await acceptNotice("e2e", { admin: true });
		await signIn(context, "e2e", { admin: true });
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR);
		const frame = await openInline(page);
		await expectWork(frame, TRACE_URL);

		const issue = await context.newPage();
		await providerTab(issue, GITLAB_ISSUE);
		const issueFrame = await openInline(issue);
		await expectWork(issueFrame, `${WEB_APP_URL}/w/ext-e2e/reviews/scm.issue/920011`);

		await stopWorker(context, page);
		const again = await context.newPage();
		await providerTab(again, GITLAB_MR5);
		const againFrame = await openInline(again);
		await expectWork(againFrame, MR5_TRACE);
	});
});

test.describe("inline review activity", () => {
	test.beforeEach(async () => {
		await acceptNotice("e2e", { admin: true });
	});

	test("links an admin to the exact work's review details, and confirms a request only in its own window", async ({
		context,
	}) => {
		await signIn(context, "e2e", { admin: true });
		const mutations = watchReviewMutations(context);
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR);
		const frame = await openInline(page);
		await expectWork(frame, TRACE_URL);
		// Every developer's records, delivery and runs are the web app's: one link, for this work.
		const details = `${WEB_APP_URL}/w/ext-e2e/admin/practices/reviews/targets/pull-request/${MR4_WORK_ID}`;
		await expectSafeLink(frame.getByRole("link", { name: /^Review details/u }), details);
		await expect(frame.getByRole("button", { name: /Manage/u })).toHaveCount(0);
		// Only the line opens; nothing inside it opens further.
		await expect(frame.locator("[aria-expanded]")).toHaveCount(1);

		// A queued run on this work holds a new request back: the line says so and offers none.
		await expect(line(frame)).toContainText("Review queued or running");
		await expect(frame.getByRole("button", { name: "Request review…" })).toHaveCount(0);

		// On work with nothing running, "Request review…" opens the extension's own window, which
		// shows what would change and sends nothing until confirmed there; cancelling sends nothing.
		const settings = await context.newPage();
		await providerTab(settings, GITLAB_MR5);
		const settingsFrame = await openInline(settings);
		const opened = context.waitForEvent("page");
		await settingsFrame.getByRole("button", { name: "Request review…" }).last().click();
		const confirmation = await opened;
		await expect(confirmation).toHaveURL(/\/action\.html#/u);
		await expect(confirmation.getByRole("heading", { level: 1 })).toHaveText(
			"Request a practice review",
		);
		// The exact work and workspace, read afresh from Hephaestus.
		await expect(confirmation.getByRole("definition").first()).toContainText("!5");
		await expect(confirmation.getByRole("definition").nth(1)).toContainText("Extension E2E");
		const closed = confirmation.waitForEvent("close");
		await confirmation.getByRole("button", { name: "Cancel" }).click();
		await closed;

		// Only the destination page is a stub: this proves safe top-level navigation and no command
		// on click. The web app owns its own session, confirmation and mutation browser coverage.
		await context.route(`${WEB_APP_URL}/w/**`, async (route) => {
			await route.fulfill({ contentType: "text/html", body: "<h1>Hephaestus destination</h1>" });
		});
		const destinationOpened = context.waitForEvent("page");
		await frame.getByRole("link", { name: /^Review details/u }).click();
		const destination = await destinationOpened;
		await destination.waitForLoadState("domcontentloaded");
		expect(await destination.evaluate(() => window.opener === null)).toBe(true);
		await destination.close();
		expect(mutations).toStrictEqual([]);
	});

	test("has no review command RPCs and refuses options authority inside the frame", async ({
		context,
		worker,
	}) => {
		await signIn(context, "e2e", { admin: true });
		const mutations = watchReviewMutations(context);
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR);
		const frame = await openInline(page);
		await expectWork(frame, TRACE_URL);
		const tabId = await tabIdOf(worker, GITLAB_MR);
		for (const message of [
			{ type: "request-review", tabId, workspaceSlug: "ext-e2e", workId: MR4_WORK_ID },
			{ type: "cancel-review", tabId, workspaceSlug: "ext-e2e", jobId: QUEUED_JOB },
			{ type: "retry-result-processing", tabId, workspaceSlug: "ext-e2e", jobId: FAILED_JOB },
			// Admin reads and run changes are the web app's; the extension has no command for them.
			{ type: "get-review-details", workspaceSlug: "ext-e2e" },
			{ type: "list-observations", workspaceSlug: "ext-e2e", scope: "workspace" },
			{
				type: "open-action",
				workspaceSlug: "ext-e2e",
				action: { kind: "cancel-review", jobId: QUEUED_JOB },
			},
			{
				type: "open-action",
				workspaceSlug: "ext-e2e",
				action: { kind: "retry-delivery", jobId: FAILED_JOB },
			},
			{ type: "set-preferred-workspace", workspaceSlug: "ext-e2e" },
			{ type: "get-context", tabId },
		]) {
			expect(await sendAs(frame, message), message.type).toMatchObject({
				ok: false,
				error: { code: "invalid" },
			});
		}
		for (const message of [
			{ type: "confirm-action", intent: "00000000-0000-4000-8000-000000000001" },
			{ type: "get-action", intent: "00000000-0000-4000-8000-000000000001" },
			{ type: "sign-out" },
			{ type: "clear-instance" },
			{ type: "configure-instance", origin: SERVER_URL, webAppOrigin: WEB_APP_URL },
			{ type: "sign-in-dev", username: "e2e", admin: true },
			{ type: "list-site-access" },
		]) {
			expect(await sendAs(frame, message), message.type).toMatchObject({
				ok: false,
				error: { code: "forbidden" },
			});
		}
		expect(mutations).toStrictEqual([]);
	});

	test("discards an old trace when the provider navigates with the answer still out", async ({
		context,
	}) => {
		await signIn(context, "e2e", { admin: true });
		const mutations = watchReviewMutations(context);
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR);
		const hold = await holdServer(context, /\/practices\/trace\/scm\.pull_request\/920010$/u);
		await openInline(page);
		await expect.poll(() => hold.hits.length).toBeGreaterThan(0);
		await page.evaluate(() => {
			history.pushState({}, "", "/ext/demo/-/merge_requests/5");
		});
		const current = await openInline(page);
		await expectWork(current, MR5_TRACE);
		await hold.release();
		await expectWork(current, MR5_TRACE);
		await expect(current.locator(`a[href="${TRACE_URL}"]`)).toHaveCount(0);
		expect(mutations).toStrictEqual([]);
	});

	test("clears the old work immediately while the new work is loading", async ({ context }) => {
		await signIn(context, "e2e", { admin: true });
		const mutations = watchReviewMutations(context);
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR);
		const old = await openInline(page);
		await expectWork(old, TRACE_URL);
		const hold = await holdServer(context, /\/practices\/trace\/scm\.pull_request\/920012$/u);
		await page.evaluate(() => {
			history.pushState({}, "", "/ext/demo/-/merge_requests/5");
		});
		const current = await openInline(page);
		await expect.poll(() => hold.hits.length).toBeGreaterThan(0);
		await expect(current.locator(`a[href="${TRACE_URL}"]`)).toHaveCount(0);
		await hold.release();
		await expectWork(current, MR5_TRACE);
		expect(mutations).toStrictEqual([]);
	});

	test("clears every open frame on sign-out even with admin answers still out", async ({
		context,
	}) => {
		const options = await signIn(context, "e2e", { admin: true });
		// Opening the report asks for the reader's observations; the answer is held.
		const hold = await holdServer(context, /\/practices\/observations$/u);
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR);
		const inline = await openInline(page);
		await expectWork(inline, TRACE_URL);
		const issue = await context.newPage();
		await providerTab(issue, GITLAB_ISSUE);
		const issueInline = await openInline(issue);
		await expectWork(issueInline, `${WEB_APP_URL}/w/ext-e2e/reviews/scm.issue/920011`);
		await expect.poll(() => hold.hits.length).toBeGreaterThan(0);
		await options.bringToFront();
		await options.getByRole("button", { name: "Sign out", exact: true }).click();
		for (const surface of [inline, issueInline]) {
			await expect(line(surface)).toContainText("Sign in to");
		}
		await hold.release();
		for (const surface of [inline, issueInline]) {
			await expect(surface.getByRole("link", { name: /^Open in Hephaestus/u })).toHaveCount(0);
			await expect(surface.getByRole("region", { name: "Your observations" })).toHaveCount(0);
			await expect(surface.getByRole("list", { name: "Comments for you" })).toHaveCount(0);
			await expect(line(surface)).toContainText("Sign in to");
		}
	});

	test("clears the current frame across an instance change before stale answers arrive", async ({
		context,
	}) => {
		const options = await signIn(context, "e2e", { admin: true });
		const hold = await holdServer(context, /\/practices\/observations$/u);
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR);
		const frame = await openInline(page);
		await expectWork(frame, TRACE_URL);
		await expect.poll(() => hold.hits.length).toBeGreaterThan(0);
		const otherOrigin = new URL(SERVER_URL);
		otherOrigin.hostname = otherLoopback(otherOrigin.hostname);
		// The same isolated server through its other loopback hostname is a distinct instance
		// identity for credential isolation, without adding a second backend or fabricating data.
		expect(
			await sendAs(options, {
				type: "configure-instance",
				origin: otherOrigin.origin,
				webAppOrigin: WEB_APP_URL,
			}),
		).toMatchObject({ ok: true });
		await expect(line(frame)).toContainText("Sign in to");
		await hold.release();
		await expect(line(frame)).toContainText("Sign in to");
		await expect(frame.locator(`a[href="${TRACE_URL}"]`)).toHaveCount(0);
		await expect(frame.getByRole("list", { name: "Comments for you" })).toHaveCount(0);
		await expect(frame.getByRole("region", { name: "Your observations" })).toHaveCount(0);
	});

	test("changes a frame's workspace without retaining the previous workspace's links", async ({
		context,
	}) => {
		await signIn(context, "e2e", { admin: true });
		const mutations = watchReviewMutations(context);
		const page = await context.newPage();
		await providerTab(page, "https://gitlab.example.test/ext/choice/-/issues/1");
		const frame = await openInline(page);
		await frame.getByRole("radio", { name: "Extension E2E", exact: true }).check();
		const primaryTrace = `${WEB_APP_URL}/w/ext-e2e/reviews/scm.issue/922010`;
		await expectWork(frame, primaryTrace);
		const hold = await holdServer(
			context,
			/\/workspaces\/ext-e2e-alternate\/practices\/trace\/scm\.issue\/922010$/u,
		);
		await frame.getByRole("radio", { name: "Extension E2E alternate", exact: true }).click();
		await expect.poll(() => hold.hits.length).toBeGreaterThan(0);
		await expect(frame.locator(`a[href="${primaryTrace}"]`)).toHaveCount(0);
		await hold.release();
		await expectWork(frame, `${WEB_APP_URL}/w/ext-e2e-alternate/reviews/scm.issue/922010`);
		await expect(
			frame.getByRole("radio", { name: "Extension E2E alternate", exact: true }),
		).toBeChecked();
		// The choice belongs to that tab's report; a report in another tab makes its own.
		const second = await context.newPage();
		await providerTab(second, "https://gitlab.example.test/ext/choice/-/issues/1");
		const secondFrame = await openInline(second);
		await expect(
			secondFrame.getByRole("radio", { name: "Extension E2E alternate", exact: true }),
		).not.toBeChecked();
		expect(mutations).toStrictEqual([]);
	});
});

test.describe("a developer who is not an admin", () => {
	test("shows recorded native comments once and navigates to them without copying their bodies", async ({
		context,
	}) => {
		await acceptNotice("e2e-dev", { admin: false });
		await signIn(context, "e2e-dev", { admin: false });
		const page = await context.newPage();
		await providerTab(page, GITLAB_MR6);
		const frame = await openInline(page);
		await expect(line(frame)).toContainText("3 comments for you");
		// Where each comment is, is the link to it: three native comments, each once.
		const comments = frame.getByRole("list", { name: "Comments for you" });
		await expect(comments.getByRole("link")).toHaveCount(3);
		for (const name of ["Summary comment", "README.md:12", "README.md:18"]) {
			await expect(comments.getByRole("link", { name, exact: true })).toHaveCount(1);
		}
		await expect(comments.getByText(/^Explain validation, Keep changes focused · /u)).toBeVisible();
		// No body, and no practice of feedback that was never posted, reaches the comments. (The
		// reader's own observations below may name that practice: they are about the work.)
		await expect(comments).not.toContainText("E2E_");
		await expect(frame.locator("body")).not.toContainText("_BODY");
		const summary = frame.locator(`a[href="${GITLAB_MR6}#note_926001"]`);
		await expect(summary).toHaveCount(1);
		await summary.click();
		await expect(page).toHaveURL(`${GITLAB_MR6}#note_926001`);
	});

	test("sees their own observations and request on their work, and no admin detail on another's", async ({
		context,
	}) => {
		await acceptNotice("e2e-dev", { admin: false });
		await signIn(context, "e2e-dev", { admin: false });
		const own = await context.newPage();
		await providerTab(own, GITLAB_MR6);
		const ownFrame = await openInline(own);
		await expectWork(ownFrame, `${WEB_APP_URL}/w/ext-e2e/reviews/scm.pull_request/920013`);
		const observations = ownFrame.getByRole("region", { name: "Your observations" });
		await expect(observations.getByRole("listitem")).toHaveCount(3);
		await expect(ownFrame.getByRole("button", { name: "Request review…" }).first()).toBeVisible();
		await expect(ownFrame.getByRole("link", { name: /^Review details/u })).toHaveCount(0);

		const other = await context.newPage();
		await providerTab(other, GITLAB_MR);
		const otherFrame = await openInline(other);
		await expectWork(otherFrame, TRACE_URL);
		await expect(otherFrame.getByRole("button", { name: "Request review…" })).toHaveCount(0);
		await expect(otherFrame.getByRole("link", { name: /^Review details/u })).toHaveCount(0);
	});
});

test("a list reads only the pressed row, and shows its preview at once without a second click", async ({
	context,
}) => {
	await acceptNotice("e2e", { admin: true });
	await signIn(context, "e2e", { admin: true });
	const reads: string[] = [];
	context.on("request", recordPracticeReads(reads));
	const page = await context.newPage();
	await page.goto("https://gitlab.example.test/ext/demo/-/merge_requests");
	await expect(page.locator("hephaestus-inspect")).toHaveCount(2);
	await expect(page.locator("hephaestus-report")).toHaveCount(0);
	expect(reads).toStrictEqual([]);
	await page.locator("hephaestus-inspect").first().getByRole("button").click();
	const frame = await reportFrame(page);
	// One line, filled at once: nothing in it opens, and it offers no request.
	await expect(frame.getByRole("status")).toContainText("3 comments for you");
	await expect(frame.locator("[aria-expanded]")).toHaveCount(0);
	await expect(frame.getByRole("button", { name: "Request review…" })).toHaveCount(0);
	await expect(frame.locator("body")).not.toContainText("E2E_");
	expect(reads.some((url) => new URL(url).pathname.endsWith("/feedback/on-work"))).toBe(true);
	expect(reads.some((url) => new URL(url).pathname.endsWith("/practices/observations"))).toBe(
		false,
	);
	expect(reads.some((url) => new URL(url).pathname.includes("/practices/reviews/"))).toBe(false);
	// A comment link takes the tab to that comment on the work.
	const first = frame.getByRole("link").first();
	const href = await first.getAttribute("href");
	expect(href?.startsWith(`${GITLAB_MR}#note_`)).toBe(true);
	await first.click();
	await expect(page).toHaveURL(required(href, "the comment link"));
});

test.describe("site access", () => {
	test("cannot remove a required fixture origin, which is the limit of this suite", async ({
		context,
	}) => {
		// Playwright cannot answer Chrome's optional-host prompt. Unit tests exercise optional
		// grants/revocation, and native Chrome validation covers the real first-grant flow.
		const options = await context.newPage();
		await options.goto(OPTIONS_URL);
		expect(await removeSite(options, "https://gitlab.example.test/*")).toMatch(/required/iu);
	});
});
