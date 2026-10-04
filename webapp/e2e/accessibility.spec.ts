import { randomUUID } from "node:crypto";

import { AxeBuilder } from "@axe-core/playwright";
import type { Locator, Page } from "@playwright/test";

import { expect, test as base } from "./fixtures";

/**
 * The integrated half of the accessibility evaluation in
 * `docs/contributor/accessibility-audit-plan.md`: the built SPA against the real server, which no
 * story can see. A story proves a component in isolation; this proves what a person meets — the
 * title of each page, the landmarks around it, the order Tab walks it in, what a narrow or
 * magnified window does to it, and what a menu or dialog does with focus.
 *
 * It decides nothing a screen reader decides. NVDA and VoiceOver are run by a person, and the
 * dated record in `docs/contributor/` says which of them has been.
 */

interface Surface {
	name: string;
	path: string;
}

const PUBLIC: Surface[] = [
	{ name: "Landing", path: "/" },
	{ name: "About", path: "/about" },
	{ name: "Imprint", path: "/imprint" },
	{ name: "Privacy", path: "/privacy" },
	{ name: "Sign in", path: "/login" },
	{ name: "Workspace sign in", path: "/w/e2e/login" },
	{ name: "Sign-in problem", path: "/auth/error" },
	{ name: "Unsubscribe", path: "/unsubscribe" },
	{ name: "Not found", path: "/no-such-page" },
];

const SIGNED_IN: Surface[] = [
	{ name: "New workspace", path: "/workspaces/new" },
	{ name: "New GitHub workspace", path: "/workspaces/new/github" },
	{ name: "New GitLab workspace", path: "/workspaces/new/gitlab" },
	{ name: "Integration callback", path: "/integrations" },
	{ name: "Settings", path: "/settings" },
	{ name: "Practice profile", path: "/w/e2e/practice-profile" },
	{ name: "Activity", path: "/w/e2e/activity" },
	{ name: "Workspace activity", path: "/w/e2e/workspace-activity" },
	{ name: "Teams", path: "/w/e2e/teams" },
	{ name: "Mentor", path: "/w/e2e/mentor" },
	{ name: "Onboarding", path: "/w/e2e/onboarding" },
	{ name: "Members", path: "/w/e2e/admin/members" },
	{ name: "Teams admin", path: "/w/e2e/admin/teams" },
	{ name: "Practices", path: "/w/e2e/admin/practices" },
	{ name: "Practice updates", path: "/w/e2e/admin/practices/releases" },
	{ name: "Review settings", path: "/w/e2e/admin/practices/review" },
	{ name: "Practice reviews", path: "/w/e2e/admin/practices/reviews" },
	{ name: "Review runs", path: "/w/e2e/admin/practices/reviews/runs" },
	{ name: "Review observations", path: "/w/e2e/admin/practices/reviews/observations" },
	{ name: "Review feedback", path: "/w/e2e/admin/practices/reviews/feedback" },
	{ name: "Reviewed work", path: "/w/e2e/admin/practices/reviews/work" },
	{ name: "AI models", path: "/w/e2e/admin/models" },
	{ name: "AI usage", path: "/w/e2e/admin/usage" },
	{ name: "Integrations", path: "/w/e2e/admin/integrations" },
	{ name: "Source control", path: "/w/e2e/admin/integrations/scm" },
	{ name: "Outline", path: "/w/e2e/admin/integrations/outline" },
	{ name: "Slack", path: "/w/e2e/admin/integrations/slack" },
	{ name: "Workspace settings", path: "/w/e2e/admin/settings" },
	{ name: "Audit log", path: "/w/e2e/admin/audit" },
	{ name: "Instance overview", path: "/admin" },
	{ name: "Instance users", path: "/admin/users" },
	{ name: "Instance workspaces", path: "/admin/workspaces" },
	{ name: "View as user", path: "/admin/workspaces/e2e/users" },
	{ name: "Instance audit log", path: "/admin/audit" },
	{ name: "Practice catalog", path: "/admin/catalog" },
	{ name: "Instance AI models", path: "/admin/models" },
	{ name: "Instance AI usage", path: "/admin/usage" },
	{ name: "Instance settings", path: "/admin/settings" },
	{ name: "Login providers", path: "/admin/login-providers" },
	{ name: "Feedback inbox", path: "/admin/feedback" },
	{ name: "Surveys", path: "/admin/surveys" },
	{ name: "Person data", path: "/admin/person-data" },
];

// More stops than any page has, so a page Tab cannot leave is told from a long one.
const MAX_TAB_STOPS = 400;

// Every page names itself ahead of the product; the fallback is the product's name alone.
const PAGE_TITLE = /^.+ · Hephaestus$/u;

const AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"];

// 400% zoom of a 1280px window is 320 CSS px wide and 200% is 640 (WCAG 2.2 SC 1.4.4 and 1.4.10).
const NARROW_WIDTHS = [320, 640];

// The theme follows the system, so the scheme is pinned: a machine set to dark would otherwise
// have the "light" scan measure the dark theme twice.
const publicTest = base.extend({
	colorScheme: "light",
	storageState: { cookies: [], origins: [] },
});
const signedInTest = base.extend({
	colorScheme: "light",
	storageState: async ({ adminSession }, use) => use(adminSession),
});

async function open(page: Page, path: string) {
	await page.goto(path);
	await page.waitForLoadState("networkidle");
}

/** What a reader whose system is set to dark gets: the theme follows the system. */
async function reloadInDarkScheme(page: Page) {
	await page.emulateMedia({ colorScheme: "dark" });
	await page.reload();
	await page.waitForLoadState("networkidle");
	await expect(page.locator("html")).toHaveClass(/dark/u);
}

/** What axe reports on the page as it is now, one line per node. */
async function axeViolations(page: Page, { overlay = false } = {}): Promise<string[]> {
	const builder = new AxeBuilder({ page }).withTags(AXE_TAGS);
	// A menu or dialog is portalled to the end of the body, outside every landmark by design: the
	// widget names itself, so `region` has nothing to say about it.
	const { violations } = await (overlay ? builder.disableRules("region") : builder).analyze();
	return violations.flatMap((violation) =>
		violation.nodes
			// An ARIA menu keeps focus by script, so its items are `tabindex="-1"` and axe, which
			// looks for a tab stop, calls a menu taller than the window unreachable. It is reached by
			// arrow keys. Fixed in axe-core after 4.13.0; drop this once the pin moves.
			// https://github.com/dequelabs/axe-core/pull/5364
			.filter(
				(node) =>
					violation.id !== "scrollable-region-focusable" || !node.html.includes('role="menu"'),
			)
			.map((node) => `${violation.id}: ${node.target.join(" ")}`),
	);
}

async function expectNoNarrowOverflow(page: Page) {
	for (const width of NARROW_WIDTHS) {
		await page.setViewportSize({ width, height: 256 });
		const overflow = await page.evaluate(
			() => document.documentElement.scrollWidth - document.documentElement.clientWidth,
		);
		expect(overflow, `scrolls sideways at ${width}px`).toBeLessThanOrEqual(0);
	}
}

/**
 * Motion that starts by itself, never ends and is not a progress indicator needs a way to stop it
 * (SC 2.2.2). Everything decorative here plays once; what loops is a spinner or a skeleton.
 */
async function loopingAnimations(page: Page): Promise<string[]> {
	return page.evaluate(() =>
		document
			.getAnimations()
			.filter((animation) => animation.effect?.getComputedTiming().iterations === Infinity)
			.flatMap((animation) => {
				const target = animation.effect instanceof KeyframeEffect ? animation.effect.target : null;
				const progress = target?.closest(
					'[data-slot="spinner"], [data-slot="skeleton"], [aria-busy="true"], [role="progressbar"], .animate-spin, .animate-pulse',
				);
				return progress || !target
					? []
					: [`${target.tagName.toLowerCase()} ${target.getAttribute("class") ?? ""}`];
			}),
	);
}

for (const [group, test, surfaces] of [
	["public", publicTest, PUBLIC],
	["signed in", signedInTest, SIGNED_IN],
] as const) {
	test.describe(`${group} surfaces`, () => {
		for (const surface of surfaces) {
			test(surface.name, async ({ page }) => {
				// A Tab walk, two scans and two reflow widths: more than the suite's default minute when
				// the server is busy.
				test.setTimeout(120_000);
				await open(page, surface.path);

				await test.step("the page has a title of its own", async () => {
					await expect(page).toHaveTitle(PAGE_TITLE);
				});
				await test.step("nothing loops with no way to stop it", async () => {
					expect(await loopingAnimations(page)).toEqual([]);
				});
				await test.step("Tab shows where it is and can leave", async () => {
					const walk = await walkTabOrder(page);
					expect(walk.problems).toEqual([]);
					expect(walk.leftDocument).toBe(true);
				});
				await test.step("axe, light", async () => {
					expect(await axeViolations(page)).toEqual([]);
				});
				await test.step("axe, dark", async () => {
					await reloadInDarkScheme(page);
					expect(await axeViolations(page)).toEqual([]);
				});
				await test.step("no sideways scrolling at 320 and 640 CSS pixels", async () => {
					await expectNoNarrowOverflow(page);
				});
			});
		}
	});
}

interface FocusStop {
	label: string;
	/** Name and position together, so the same control twice in a row is told from two like it. */
	at: string;
	problem?: string;
}

const COVERED = "covered by another element";

async function inspectFocus(page: Page) {
	return page.evaluate((covered): FocusStop | null => {
		const element = document.activeElement;
		if (!element || element === document.body) {
			return null;
		}
		const box = element.getBoundingClientRect();
		const style = getComputedStyle(element);
		const indicated =
			(style.outlineStyle !== "none" && Number.parseFloat(style.outlineWidth) > 0) ||
			style.boxShadow !== "none";
		// Level AA asks only that the control is not entirely hidden, so one of five sample points
		// reaching it is enough.
		const obscured = [
			[0.5, 0.5],
			[0.1, 0.1],
			[0.9, 0.1],
			[0.1, 0.9],
			[0.9, 0.9],
		].every(([x = 0, y = 0]) => {
			const hit = document.elementFromPoint(box.left + box.width * x, box.top + box.height * y);
			return !hit || !(element.contains(hit) || hit.contains(element));
		});
		const name = element.getAttribute("aria-label") ?? element.textContent;
		const label = `${element.tagName.toLowerCase()} "${name.trim().slice(0, 40)}"`;
		const at = `${label} ${box.top}|${box.left}`;
		if (!indicated) {
			return { label, at, problem: "no focus indicator" };
		}
		return obscured ? { label, at, problem: covered } : { label, at };
	}, COVERED);
}

/**
 * Walks Tab from the top of the page until it leaves the document or comes back to the skip link,
 * which is where the browsers differ, and reports each stop that shows no focus indicator (SC 2.4.7)
 * or that something else sits on top of (SC 2.4.11).
 */
async function walkTabOrder(page: Page) {
	const problems: string[] = [];
	let leftDocument = false;
	let firstStop: string | undefined;
	let previousStop: string | undefined;
	// `scroll-smooth` is on the root, and a focused control is still moving while it scrolls.
	await page.addStyleTag({ content: "html { scroll-behavior: auto !important; }" });
	for (let step = 0; step < MAX_TAB_STOPS; step += 1) {
		await page.keyboard.press("Tab");
		let stop = await inspectFocus(page);
		if (stop?.problem === COVERED) {
			// WebKit scrolls a control inside a scrolling list into view a frame after focusing it, so
			// only a control that stays covered is a finding.
			await expect(async () => {
				stop = await inspectFocus(page);
				expect(stop?.problem).not.toBe(COVERED);
			})
				.toPass({ timeout: 1000 })
				.catch(() => undefined);
		}
		// Chromium hands focus to the browser's own controls after the last stop, Firefox wraps to the
		// first, and headless Firefox stays on the last: any of the three is Tab leaving the page.
		if (!stop || stop.label === firstStop || stop.at === previousStop) {
			leftDocument = true;
			break;
		}
		firstStop ??= stop.label;
		previousStop = stop.at;
		if (stop.problem !== undefined) {
			problems.push(`${stop.label}: ${stop.problem}`);
		}
	}
	// Firefox leaves focus on the last stop, and a tooltip on it is still fading in when axe measures.
	await page.evaluate(() => {
		if (document.activeElement instanceof HTMLElement) {
			document.activeElement.blur();
		}
	});
	await expect(page.locator('[data-slot="tooltip-content"]')).toHaveCount(0);
	return { problems, leftDocument };
}

base.describe("keyboard", () => {
	publicTest("the skip link is the first stop and moves focus to the page", async ({ page }) => {
		await open(page, "/about");
		await page.keyboard.press("Tab");
		const skip = page.getByRole("link", { name: "Skip to main content" });
		await expect(skip).toBeFocused();
		await expect(skip).toBeInViewport();

		await page.keyboard.press("Enter");
		await expect(page.getByRole("main")).toBeFocused();
	});

	signedInTest(
		"a menu takes focus, passes axe, and gives focus back on Escape",
		async ({ page }) => {
			await open(page, "/w/e2e/admin/practices");
			const account = page.getByRole("button", { name: "Account" });
			await account.focus();
			await page.keyboard.press("Enter");

			const menu = page.getByRole("menu");
			await expect(menu.getByRole("menuitem", { name: "Settings" })).toBeVisible();
			await expect(menu).toContainText("Sign out");
			expect(await axeViolations(page, { overlay: true })).toEqual([]);

			await page.keyboard.press("Escape");
			await expect(menu).toBeHidden();
			await expect(account).toBeFocused();
		},
	);

	signedInTest(
		"a dialog takes focus, passes axe, and gives focus back on Escape",
		async ({ page }) => {
			await open(page, "/w/e2e/admin/practices");
			const trigger = page.getByRole("button", { name: "Create group" });
			await trigger.focus();
			await page.keyboard.press("Enter");

			const dialog = page.getByRole("dialog", { name: "Create group" });
			await expect(dialog).toBeVisible();
			await expect(dialog.getByRole("textbox", { name: "Name" })).toBeFocused();
			expect(await axeViolations(page, { overlay: true })).toEqual([]);

			await page.keyboard.press("Escape");
			await expect(dialog).toBeHidden();
			await expect(trigger).toBeFocused();
		},
	);

	signedInTest(
		"collapsed to icons, the sidebar still names each link and its tooltip can be dismissed",
		async ({ page }) => {
			await open(page, "/w/e2e/admin/members");
			await page.getByRole("banner").getByRole("button", { name: "Toggle sidebar" }).click();
			// The header slides under the pointer as the sidebar narrows and would open its tooltip.
			await page.mouse.move(640, 600);
			await expect(page.locator('[data-slot="tooltip-content"]')).toHaveCount(0);
			// The group labels fade out, and contrast measured mid-fade is the fade's.
			await page.waitForFunction(() => document.getAnimations().length === 0);
			expect(await axeViolations(page)).toEqual([]);

			const link = page
				.getByRole("navigation", { name: "Primary" })
				.getByRole("link", { name: "Activity", exact: true });
			// Arrived at by key, since a tooltip opens for the keyboard and not for a script's focus().
			await link.focus();
			await page.keyboard.press("Shift+Tab");
			await page.keyboard.press("Tab");
			await expect(link).toBeFocused();
			// Focus alone shows it, the pointer may rest on it, and Escape dismisses it without moving
			// focus (SC 1.4.13). Base UI gives the popup no role, since it repeats the control's name.
			const tooltip = page.locator('[data-slot="tooltip-content"][data-open]', {
				hasText: /^Activity$/u,
			});
			await expect(tooltip).toHaveText("Activity");
			await tooltip.hover();
			await expect(tooltip).toBeVisible();
			await page.keyboard.press("Escape");
			await expect(tooltip).toBeHidden();
			await expect(link).toBeFocused();
		},
	);

	signedInTest("following a link tells a screen reader the page it reached", async ({ page }) => {
		await open(page, "/w/e2e/admin/members");
		const announcer = page.locator('[data-slot="route-announcer"]');
		await expect(announcer).toHaveText("");

		await page
			.getByRole("navigation", { name: "Primary" })
			.getByRole("link", { name: "AI usage" })
			.click();
		await expect(announcer).toHaveText("AI usage · Admin · Hephaestus");

		// A change of search is the same page, so there is nothing new to say.
		await page.getByRole("link", { name: "Previous month" }).click();
		await expect(page).toHaveURL(/month=/u);
		await expect(announcer).toHaveText("AI usage · Admin · Hephaestus");
	});
});

/** Presses Tab until the control has focus, the way a person without a pointer reaches it. */
async function tabTo(page: Page, target: Locator) {
	for (let step = 0; step < 40; step += 1) {
		await page.keyboard.press("Tab");
		if (await target.evaluate((element) => element === document.activeElement)) {
			return;
		}
	}
	throw new Error("Tab never reached the control");
}

base.describe("complete processes, keyboard only", () => {
	publicTest("a new person signs in and accepts the terms", async ({ page }) => {
		await open(page, "/login");
		await tabTo(page, page.getByPlaceholder("username"));
		await page.keyboard.type(`a11y-${randomUUID().slice(0, 8)}`);
		await page.keyboard.press("Enter");

		await expect(page).toHaveTitle("Terms and consent · Hephaestus");
		const terms = page.getByRole("checkbox", { name: /terms/iu });
		await tabTo(page, terms);
		await expect(page.getByRole("button", { name: "Continue" })).toBeDisabled();
		await page.keyboard.press("Space");
		await expect(terms).toBeChecked();

		await tabTo(page, page.getByRole("button", { name: "Continue" }));
		await page.keyboard.press("Enter");
		await expect(page).not.toHaveTitle("Terms and consent · Hephaestus");
		expect(await axeViolations(page)).toEqual([]);
	});

	signedInTest("a practice group is created, shown and deleted", async ({ page }) => {
		const name = `Keyboard ${randomUUID().slice(0, 8)}`;
		await open(page, "/w/e2e/admin/practices");

		await page.getByRole("button", { name: "Create group" }).focus();
		await page.keyboard.press("Enter");
		const dialog = page.getByRole("dialog", { name: "Create group" });
		await expect(dialog.getByRole("textbox", { name: "Name" })).toBeFocused();
		await page.keyboard.type(name);
		await tabTo(page, dialog.getByRole("button", { name: "Create", exact: true }));
		await page.keyboard.press("Enter");

		await expect(dialog).toBeHidden();
		await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();

		await page.getByRole("button", { name: `More actions for ${name}` }).focus();
		await page.keyboard.press("Enter");
		await page.getByRole("menuitem", { name: "Delete group" }).focus();
		await page.keyboard.press("Enter");
		const confirm = page.getByRole("alertdialog");
		await expect(confirm).toBeVisible();
		await tabTo(page, confirm.getByRole("button", { name: /^Delete/u }));
		await page.keyboard.press("Enter");

		await expect(page.getByRole("heading", { level: 2, name })).toBeHidden();
	});

	signedInTest("an idea is sent to the instance administrators", async ({ page }) => {
		await open(page, "/w/e2e/admin/members");
		await page.getByRole("banner").getByRole("button", { name: "Feedback" }).focus();
		await page.keyboard.press("Enter");
		await page.getByRole("menuitem", { name: "Share an idea" }).focus();
		await page.keyboard.press("Enter");

		const dialog = page.getByRole("dialog", { name: "Share an idea" });
		await expect(dialog).toBeVisible();
		await tabTo(page, dialog.getByRole("textbox"));
		await page.keyboard.type("Let the keyboard reach every chart tooltip.");
		await tabTo(page, dialog.getByRole("button", { name: /^Send/u }));
		await page.keyboard.press("Enter");

		await expect(dialog).toBeHidden();
	});
});
