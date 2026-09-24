import { fireEvent, screen, within } from "@testing-library/react";
import { assert, describe, expect, it, vi } from "vitest";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import { renderWithRouter } from "@/test/router-harness";

import { eurRate, usageReport, withOwnProvider } from "./fixtures";
import { WorkspaceLlmUsagePage, type WorkspaceLlmUsagePageProps } from "./WorkspaceLlmUsagePage";

const pricedReport = withOwnProvider(usageReport("2026-07"));

/** Two Mentor turns without a price: enough to make the no-price warnings and the pause banners speak. */
const baseReport: WorkspaceLlmUsageReport = {
	...pricedReport,
	unpricedEventCount: 2,
	byJobType: pricedReport.byJobType.map((row) =>
		row.jobType === "MENTOR_TURN" ? { ...row, unpricedEventCount: 2 } : row,
	),
	byDay: pricedReport.byDay.map((row, index) =>
		index === pricedReport.byDay.length - 1 ? { ...row, unpricedEventCount: 2 } : row,
	),
};

async function renderPage(
	report: WorkspaceLlmUsageReport = baseReport,
	props: Partial<React.ComponentProps<typeof WorkspaceLlmUsagePage>> = {},
) {
	await renderWithRouter(
		<WorkspaceLlmUsagePage
			month="2026-07"
			isCurrentMonth
			canGoNext={false}
			workspaceSlug="acme"
			view={{ status: "ready", report }}
			onEditOwnProviderCap={vi.fn()}
			now={new Date("2026-07-10T12:00:00.000Z")}
			{...props}
		/>,
		"/w/acme/admin/usage",
	);
	await screen.findByRole("heading", { name: "AI usage" });
}

/** The banner's fix link, or null where the reader is not the one who can apply it. */
function fixLinkHref(banner: HTMLElement) {
	return (
		within(banner).queryByRole("link", { name: "Open AI models" })?.getAttribute("href") ?? null
	);
}

/** The pace warning, or null where the page deliberately stays quiet. */
function paceNote() {
	return screen.queryByRole("status")?.textContent ?? null;
}

describe("WorkspaceLlmUsagePage", () => {
	it("separates shared-model and provider spend in every rollup", async () => {
		await renderPage();

		screen.getByText("Shared-model spend so far");
		screen.getByText("Shared-model budget · set by your host");
		screen.getByText("Your provider spend so far");
		screen.getByText("Provider cap · set by you, billed by your provider");

		const byJobType = screen.getByRole("table", { name: "AI spend by run type" });
		within(byJobType).getByRole("columnheader", { name: "Shared models" });
		within(byJobType).getByRole("columnheader", { name: "Your provider" });
		within(byJobType).getByRole("columnheader", { name: "No price set" });
		within(byJobType).getByText("$8.20");
		within(byJobType).getByText("$1.92");

		const byDay = screen.getByRole("table", { name: "AI spend by day" });
		within(byDay).getByRole("columnheader", { name: "Shared models" });
		within(byDay).getByRole("columnheader", { name: "Your provider" });
		within(byDay).getByRole("columnheader", { name: "No price set" });
		within(byDay).getByText("$5.63");
		within(byDay).getByText("$1.92");
	});

	it("gives each cap its own meter, named for whose money it is", async () => {
		await renderPage();

		screen.getByRole("progressbar", { name: "Shared-model budget used" });
		screen.getByRole("progressbar", { name: "Your provider cap used" });
	});

	it("gives the right pricing owner an actionable no-price-set warning", async () => {
		await renderPage();

		screen.getByText("2 runs aren't counted in these totals");
		screen.getByText(/Add prices for your own models in .*; for shared models, ask your host\./u);
	});

	it("averages each purse over the run count on its own, never the two summed", async () => {
		await renderPage();

		const byJobType = screen.getByRole("table", { name: "AI spend by run type" });
		within(byJobType).getByRole("columnheader", { name: "Avg per run" });
		const mentorTurns = within(byJobType).getByRole("row", { name: /^Mentor turn/u });
		within(mentorTurns).getByText("$0.06");
		within(mentorTurns).getByText("shared models");
		within(mentorTurns).getByText("$0.03");
		within(mentorTurns).getByText("your provider");
	});

	describe("pause banners", () => {
		it.each<[string, Partial<WorkspaceLlmUsageReport>, string, string, string | null]>([
			[
				"a reached provider cap",
				{
					ownProviderPaused: true,
					ownProviderBudgetVerdict: "EXHAUSTED",
					ownProviderTotalCostUsd: 10,
				},
				"Your provider cap is reached",
				"Paused until August 1 (UTC), or until you raise or remove the cap.",
				null,
			],
			[
				"an unenforceable provider cap",
				{ ownProviderPaused: true, ownProviderBudgetVerdict: "UNVERIFIABLE" },
				"Your provider cap can't be enforced",
				"2 runs on your models have no price, so the cap can't be checked and your provider is paused. Add a price to resume, or remove the cap.",
				"/w/acme/admin/models",
			],
			[
				"an exhausted shared budget",
				{ instancePaused: true, instanceBudgetVerdict: "EXHAUSTED", instanceTotalCostUsd: 25 },
				"Shared-model budget reached",
				"Paused until August 1 (UTC), or until your host raises the budget. Practice reviews and Mentor can keep running on your own models.",
				"/w/acme/admin/models",
			],
			[
				"an unverifiable shared budget",
				{ instancePaused: true, instanceBudgetVerdict: "UNVERIFIABLE" },
				"Shared-model spend can't be verified",
				"2 shared-model runs have no price, so the budget can't be checked and shared models are paused. Only your host can price them.",
				null,
			],
		])(
			"explains %s, and links to the fix only where the reader can apply it",
			async (_name, patch, title, body, href) => {
				await renderPage({ ...baseReport, ...patch });

				const banner = screen.getByText(title).closest("[role='alert']");
				assert(banner instanceof HTMLElement, `Pause banner "${title}" not found`);
				within(banner).getByText(body);
				expect(fixLinkHref(banner)).toBe(href);
			},
		);

		it("puts the cap editor in the banner as a button, not a link away to another owner", async () => {
			const onEditOwnProviderCap = vi.fn<WorkspaceLlmUsagePageProps["onEditOwnProviderCap"]>();
			await renderPage(
				{
					...baseReport,
					ownProviderPaused: true,
					ownProviderBudgetVerdict: "EXHAUSTED",
					ownProviderTotalCostUsd: 10,
				},
				{ onEditOwnProviderCap },
			);

			const banner = screen.getByText("Your provider cap is reached").closest("[role='alert']");
			assert(banner instanceof HTMLElement, "Provider pause banner not found");
			const adjust = within(banner).getByRole("button", { name: "Adjust cap" });

			fireEvent.click(adjust);

			expect(onEditOwnProviderCap).toHaveBeenCalledOnce();
			expect(adjust.tagName).toBe("BUTTON");
		});

		it("shows no pause banner for a past month", async () => {
			await renderPage(
				{ ...baseReport, ownProviderPaused: true, ownProviderBudgetVerdict: "EXHAUSTED" },
				{ month: "2026-06", isCurrentMonth: false },
			);

			expect(screen.queryByText("Your provider cap is reached")).toBeNull();
		});
	});

	describe("approaching a cap", () => {
		it.each<[string, Partial<WorkspaceLlmUsageReport>, Date, string | null]>([
			[
				"warns at 80% with the date the pace reaches the cap",
				{ ownProviderTotalCostUsd: 8.4 },
				new Date("2026-07-10T12:00:00.000Z"),
				"You've used 84% of your provider cap$8.40 of $10. At this pace, the cap is reached around July 12.",
			],
			[
				"keeps the warning but withholds a projection the month is too young to support",
				{ ownProviderTotalCostUsd: 8.4 },
				new Date("2026-07-02T12:00:00.000Z"),
				"You've used 84% of your provider cap$8.40 of $10.",
			],
			["stays quiet below the threshold", {}, new Date("2026-07-10T12:00:00.000Z"), null],
			[
				"says nothing about a cap that is already paused, which the banner covers",
				{
					ownProviderTotalCostUsd: 10,
					ownProviderPaused: true,
					ownProviderBudgetVerdict: "EXHAUSTED",
				},
				new Date("2026-07-10T12:00:00.000Z"),
				null,
			],
		])("%s", async (_name, patch, now, pace) => {
			await renderPage({ ...pricedReport, ...patch }, { now });

			expect(paceNote()).toBe(pace);
		});
	});

	describe("provider card", () => {
		it.each<[string, Partial<WorkspaceLlmUsageReport>, string | RegExp]>([
			[
				"nothing has run on it",
				{ ownProviderTotalCostUsd: 0, byJobType: [], byDay: [] },
				/Connect your own provider in/u,
			],
			["there is uncapped spend", {}, "No provider cap set · billed to you by your provider"],
		])("offers a cap when %s", async (_name, patch, copy) => {
			const onEditOwnProviderCap = vi.fn<WorkspaceLlmUsagePageProps["onEditOwnProviderCap"]>();
			await renderPage(
				{ ...baseReport, ownProviderMonthlyBudgetUsd: undefined, ...patch },
				{ onEditOwnProviderCap },
			);

			screen.getByText(copy);
			fireEvent.click(screen.getByRole("button", { name: "Set cap" }));
			expect(onEditOwnProviderCap).toHaveBeenCalledOnce();
		});

		it.each<[string, Partial<WorkspaceLlmUsageReport>, string]>([
			["a cap in force", {}, "Change cap"],
			["no cap yet", { ownProviderMonthlyBudgetUsd: undefined }, "Set cap"],
		])(
			"withdraws the editor on a closed month and says where to change it, with %s",
			async (_name, patch, label) => {
				await renderPage(
					{ ...baseReport, month: "2026-06", ...patch },
					{ month: "2026-06", isCurrentMonth: false },
				);

				expect(screen.queryByRole("button", { name: label })).toBeNull();
				screen.getByText(
					"A cap applies from the moment it is saved, not to the month you are reading. Step forward to this month to change it.",
				);
			},
		);
	});

	describe("display currency", () => {
		const twoDaysWithATotalRow: WorkspaceLlmUsageReport["byDay"] = [
			{
				day: new Date("2026-07-05T00:00:00.000Z"),
				instanceTotalCostUsd: 6.2,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				events: 2,
			},
			{
				day: new Date("2026-07-06T00:00:00.000Z"),
				instanceTotalCostUsd: 6.2,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				events: 2,
			},
		];

		/** No caps anywhere, so the day table's footer is the only thing that can convert. */
		const uncapped: WorkspaceLlmUsageReport = {
			...pricedReport,
			instanceMonthlyBudgetUsd: 0,
			ownProviderMonthlyBudgetUsd: undefined,
			ownProviderTotalCostUsd: 0,
			byJobType: [],
			fx: eurRate,
		};

		it("discloses the rate when only the table footers convert", async () => {
			await renderPage({ ...uncapped, byDay: twoDaysWithATotalRow, instanceTotalCostUsd: 12.4 });

			const table = screen.getByRole("table", { name: "AI spend by day" });
			expect(within(table).getByRole("row", { name: /^Total/u }).textContent).toContain("≈ €10.90");
			screen.getByText(/reference rate published on/u);
		});

		it("says nothing about the rate when nothing on the page converted", async () => {
			await renderPage({ ...uncapped, byDay: [], instanceTotalCostUsd: 0 });

			expect(screen.queryByText(/reference rate published on/u)).toBeNull();
		});

		it("stays silent under a cap that is set but converted nowhere on the page", async () => {
			await renderPage({
				...pricedReport,
				instanceMonthlyBudgetUsd: undefined,
				ownProviderMonthlyBudgetUsd: 50,
				instanceTotalCostUsd: 0,
				ownProviderTotalCostUsd: 0,
				byJobType: [],
				byDay: [],
				fx: eurRate,
			});

			expect(screen.queryByText(/≈ €/u)).toBeNull();
			expect(screen.queryByText(/reference rate published on/u)).toBeNull();
		});

		it("converts the projected month-end figure in the same breath as the spend it follows", async () => {
			await renderPage(
				{
					...pricedReport,
					instanceMonthlyBudgetUsd: 50,
					instanceTotalCostUsd: 43.9,
					ownProviderMonthlyBudgetUsd: undefined,
					ownProviderTotalCostUsd: 0,
					fx: eurRate,
				},
				{ now: new Date("2026-07-28T12:00:00.000Z") },
			);

			const alert = screen.getByText(/At this pace/u);
			expect(alert.textContent).toContain("≈ €38.59 of €44");
			expect(alert.textContent).toMatch(/the month finishes around \$[\d.]+ \(≈ €[\d.]+\)\./u);
		});
	});
});
