import type { Locator, Page } from "@playwright/test";

import { expect, loginAsDevAdmin, test } from "./fixtures";

// The packaged-server suite runs without a worker (`application-playwright.yml`), so a question stays queued:
// these tests prove what the app does around a turn, and `heph.live.spec.ts` proves an answer.
const SEEDED_ANSWER = "e2e0c0a0-0000-4000-8000-000000000003";

/** A question nobody else asked, so a test finds its own message in a conversation that outlives it. */
function uniqueQuestion(label: string): string {
	return `${label} ${crypto.randomUUID().slice(0, 8)}`;
}

function conversation(page: Page): Locator {
	return page.getByRole("region", { name: "Conversation with Heph" });
}

/** The timeline item that carries `text`; its actions appear on hover or focus. */
function message(scope: Locator, text: string): Locator {
	return scope.locator("[data-message-id]").filter({ hasText: text });
}

async function messageId(item: Locator): Promise<string> {
	const id = await item.getAttribute("data-message-id");
	expect(id, "a timeline item names its message").toBeTruthy();
	return id ?? "";
}

async function ask(page: Page, text: string): Promise<Locator> {
	await page.getByRole("textbox", { name: "Message Heph" }).fill(text);
	await page.getByRole("button", { name: "Send message" }).click();
	const item = message(conversation(page), text);
	// The question shows at once under its request id, then under the id the server stored it with.
	await expect(item).toHaveAttribute("data-message-id", /^[\da-f]{8}-[\da-f-]{27}$/u);
	return item;
}

/** Stops every question this test left waiting, so the next test starts with room in the queue. */
async function stopWaiting(page: Page): Promise<void> {
	const stops = conversation(page).getByRole("button", {
		name: /^Stop (?:queued message|Heph) \(/u,
	});
	for (let waiting = await stops.count(); waiting > 0; waiting -= 1) {
		await stops.first().click();
		// Stopping the turn Heph is on makes the next queued question the live one, so one button goes.
		await expect(stops).toHaveCount(waiting - 1);
	}
	for (const name of ["Stop Heph", "Stop Heph in this thread"]) {
		const composerStop = page.getByRole("button", { name, exact: true });
		if (await composerStop.isVisible()) {
			await composerStop.click();
		}
	}
}

/** A retried run starts from no rating, whichever one the failed run left. */
async function clearVote(...buttons: Locator[]): Promise<void> {
	for (const button of buttons) {
		if ((await button.getAttribute("aria-pressed")) === "true") {
			await button.click();
			await expect(button).toHaveAttribute("aria-pressed", "false");
		}
	}
}

test.beforeEach(async ({ page }) => {
	await loginAsDevAdmin(page);
	await page.goto("/w/e2e/mentor");
	await expect(page.getByRole("textbox", { name: "Message Heph" })).toBeVisible();
	await stopWaiting(page);
});

test.afterEach(async ({ page }) => {
	await stopWaiting(page);
});

test("a question waits for Heph, follow-ups queue behind it, and a fourth is refused", async ({
	page,
}) => {
	const first = await ask(page, uniqueQuestion("First question"));
	// No worker starts it, so after its first second it says it waits for Heph.
	await expect(
		page.getByRole("status").filter({ hasText: "Heph is answering other questions first…" }),
	).toBeAttached();

	const second = await ask(page, uniqueQuestion("Second question"));
	await expect(second.getByText("Queued. Heph answers this next.")).toBeVisible();
	const third = await ask(page, uniqueQuestion("Third question"));
	await expect(third.getByText("Queued.", { exact: true })).toBeVisible();

	await page.getByRole("textbox", { name: "Message Heph" }).fill(uniqueQuestion("Fourth question"));
	await page.getByRole("button", { name: "Send message" }).click();
	await expect(
		page.getByText("Heph is still answering. Wait for an answer before you send more messages."),
	).toBeVisible();

	await second.hover();
	await second.getByRole("button", { name: /^Stop queued message \(/u }).click();
	await expect(second.getByText("Stopped", { exact: true })).toBeVisible();
	await expect(first).toBeVisible();
});

test("a thread opens on a question, takes a reply, and opens again from its link", async ({
	page,
}) => {
	const parentText = uniqueQuestion("Thread parent");
	const parent = await ask(page, parentText);
	const parentId = await messageId(parent);

	await parent.hover();
	await parent.getByRole("button", { name: /^Reply in thread \(/u }).click();
	const panel = page.getByRole("complementary", { name: "Thread" });
	await expect(panel).toBeVisible();
	const replyText = uniqueQuestion("Thread reply");
	await panel.getByRole("textbox", { name: "Reply in thread" }).fill(replyText);
	await panel.getByRole("button", { name: "Send message" }).click();
	await expect(message(panel, replyText)).toBeVisible();
	await stopWaiting(page);

	await page.getByRole("button", { name: "Close thread" }).click();
	await expect(panel).toBeHidden();

	await page.goto(`/w/e2e/mentor?thread=${parentId}`);
	await expect(
		message(page.getByRole("complementary", { name: "Thread" }), replyText),
	).toBeVisible();
});

test("a link to one message brings it into view", async ({ page }) => {
	const target = await ask(page, uniqueQuestion("Linked question"));
	const targetId = await messageId(target);
	await stopWaiting(page);

	await page.goto(`/w/e2e/mentor?entry=${targetId}`);
	await expect(conversation(page).locator(`[data-message-id="${targetId}"]`)).toBeInViewport();

	await page.goto("/w/e2e/mentor?entry=00000000-0000-4000-8000-000000000000");
	await expect(
		page.getByText("We could not find that message. It may have been deleted."),
	).toBeVisible();
});

test("a developer deletes their own question and Heph never answers it", async ({ page }) => {
	const text = uniqueQuestion("Question to delete");
	const item = await ask(page, text);
	const id = await messageId(item);

	await item.hover();
	await item.getByRole("button", { name: /^Delete message \(/u }).click();
	const dialog = page.getByRole("alertdialog", { name: "Delete this message?" });
	await expect(
		dialog.getByText("Heph forgets it. If Heph has not answered it yet, Heph does not answer it."),
	).toBeVisible();
	await dialog.getByRole("button", { name: "Delete message" }).click();

	const deleted = conversation(page).locator(`[data-message-id="${id}"]`);
	await expect(deleted.getByText("This message was deleted.")).toBeVisible();
	await expect(deleted).not.toContainText(text);
});

test("a developer rates one of Heph's answers and can take the rating back", async ({ page }) => {
	const answer = conversation(page).locator(`[data-message-id="${SEEDED_ANSWER}"]`);
	await page.goto(`/w/e2e/mentor?entry=${SEEDED_ANSWER}`);
	await expect(answer).toBeVisible();
	await answer.hover();
	const helpful = answer.getByRole("button", { name: /^Helpful \(/u });
	const notHelpful = answer.getByRole("button", { name: /^Not helpful \(/u });
	await clearVote(helpful, notHelpful);

	await helpful.click();
	await expect(helpful).toHaveAttribute("aria-pressed", "true");
	await notHelpful.click();
	await expect(notHelpful).toHaveAttribute("aria-pressed", "true");
	await expect(helpful).toHaveAttribute("aria-pressed", "false");
	await notHelpful.click();
	await expect(notHelpful).toHaveAttribute("aria-pressed", "false");
});
