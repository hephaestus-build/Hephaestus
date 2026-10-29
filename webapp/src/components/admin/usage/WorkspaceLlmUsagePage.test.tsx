import { fireEvent, screen, within } from "@testing-library/react";
import { assert, describe, expect, it, vi } from "vitest";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import { renderWithRouter } from "@/test/router-harness";

import { eurRate, usageReport, withOwnProvider } from "./fixtures";
import { WorkspaceLlmUsagePage, type WorkspaceLlmUsagePageProps } from "./WorkspaceLlmUsagePage";

const pricedReport = withOwnProvider(usageReport("2026-07"));

/** Two conversation turns without a price: enough to make the no-price warnings and the pause banners speak. */
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

/** A paragraph by its whole text, which can run through a link. */
function paragraphContaining(text: string) {
	return (_: string, element: Element | null) =>
		element?.tagName === "P" && element.textContent.includes(text);
}

/** The pace warning, or null where the page deliberately stays quiet. */
function paceNote() {
	return screen.queryByRole("status")?.textContent ?? null;
}

describe("WorkspaceLlmUsagePage", () => {
	it("separates shared-model and provider spend in every rollup", async () => {
		await renderPage();

		const tiles = screen.getByRole("region", { name: "Spend this month" });
		within(tiles).getByText("of $25");
		within(tiles).getByText("Set by an instance admin");
		within(tiles).getByText("of $10");
		within(tiles).getByText("Billed to you by your provider");

		const byJobType = screen.getByRole("table", { name: "AI spend by run type" });
		within(byJobType).getByRole("columnheader", { name: "Shared models" });
		within(byJobType).getByRole("columnheader", { name: "Own provider" });
		within(byJobType).getByRole("columnheader", { name: "Unavailable cost" });
		within(byJobType).getByText("$8.20");
		within(byJobType).getByText("$1.92");

		const byDay = screen.getByRole("table", { name: "AI spend by day" });
		within(byDay).getByRole("columnheader", { name: "Shared models" });
		within(byDay).getByRole("columnheader", { name: "Own provider" });
		within(byDay).getByRole("columnheader", { name: "Unavailable cost" });
		within(byDay).getByText("$5.63");
		within(byDay).getByText("$1.92");
	});

	it("leaves the own-provider columns out of every table while the workspace has none", async () => {
		await renderPage(usageReport("2026-07"));

		for (const name of ["AI spend by run type", "AI spend by day"]) {
			const table = screen.getByRole("table", { name });
			expect(within(table).queryByRole("columnheader", { name: "Own provider" })).toBeNull();
			expect(within(table).queryByRole("columnheader", { name: "Unavailable cost" })).toBeNull();
		}
		screen.getByText(/^Work on a provider you connect in/u);
		expect(screen.queryByRole("button", { name: /provider cap/u })).toBeNull();
	});

	it("gives each cap its own meter, named for whose money it is", async () => {
		await renderPage();

		screen.getByRole("progressbar", { name: "Shared-model budget used" });
		screen.getByRole("progressbar", { name: "Your provider cap used" });
	});

	it("gives the right pricing owner an actionable no-price-set warning", async () => {
		await renderPage();

		screen.getByText("2 runs have unavailable cost");
		screen.getByText(
			paragraphContaining(
				"Adding rates repairs only verified usage. Set rates for your own models in AI models. For shared models, ask an instance admin.",
			),
		);
	});

	it("averages each purse over the run count on its own, never the two summed", async () => {
		await renderPage();

		const byJobType = screen.getByRole("table", { name: "AI spend by run type" });
		expect(within(byJobType).getAllByRole("columnheader", { name: "Avg per run" })).toHaveLength(2);
		const mentorTurns = within(byJobType).getByRole("row", { name: /^Conversation answer/u });
		expect(
			within(mentorTurns)
				.getAllByRole("cell")
				.slice(1, 5)
				.map((cell) => cell.textContent),
		).toStrictEqual(["$3.84", "$0.06", "$1.92", "$0.03"]);
	});

	it("names the practice reviews this month ran, beside the figures they used", async () => {
		const review = pricedReport.byJobType[0];
		assert(review);
		await renderPage({
			...pricedReport,
			byJobType: [{ ...review, jobType: "PRACTICE_REVIEW" }, ...pricedReport.byJobType.slice(1)],
		});

		const byJobType = screen.getByRole("table", { name: "AI spend by run type" });
		const cells = within(within(byJobType).getByRole("row", { name: /^Practice review/u }))
			.getAllByRole("cell")
			.map((cell) => cell.textContent);
		expect([cells[0], cells[1], cells[5], cells.at(-1)]).toStrictEqual([
			"Practice review",
			"$8.20",
			"41",
			"312",
		]);
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
				"Your provider cap cannot be enforced",
				"Your provider is paused because some usage has no confirmed cost. Rates may be missing or measurements unverified. Adding rates repairs only verified usage; removing the cap also resumes calls.",
				"/w/acme/admin/models",
			],
			[
				"an exhausted shared budget",
				{ instancePaused: true, instanceBudgetVerdict: "EXHAUSTED", instanceTotalCostUsd: 25 },
				"Shared-model budget reached",
				"Paused until August 1 (UTC), or until an instance admin raises the budget. Practice reviews and conversations can keep running on your own models.",
				"/w/acme/admin/models",
			],
			[
				"an unverifiable shared budget",
				{ instancePaused: true, instanceBudgetVerdict: "UNVERIFIABLE" },
				"Shared-model spend cannot be verified",
				"Shared models are paused because some usage has no confirmed cost. Rates may be missing or measurements unverified. Only an instance admin can add shared-model rates, and only verified usage can be repriced.",
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
				"You’ve used 84% of your provider cap$8.40 of $10. At this pace, the cap is reached around July 12.",
			],
			[
				"keeps the warning but withholds a projection the month is too young to support",
				{ ownProviderTotalCostUsd: 8.4 },
				new Date("2026-07-02T12:00:00.000Z"),
				"You’ve used 84% of your provider cap$8.40 of $10.",
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
		it("offers a provider cap once the provider is in use, before its first call", async () => {
			await renderPage({
				...baseReport,
				ownProviderMonthlyBudgetUsd: undefined,
				ownProviderTotalCostUsd: 0,
				byJobType: [],
				byDay: [],
			});

			screen.getByRole("button", { name: "Set provider cap" });
		});

		it("names the purse its cap belongs to", async () => {
			await renderPage(baseReport);

			screen.getByRole("button", { name: "Change provider cap" });
		});

		it("offers no cap while the provider is not in use, whatever the cap and spend fields say", async () => {
			await renderPage({ ...baseReport, ownProviderInUse: false, byJobType: [], byDay: [] });
			expect(screen.queryByRole("button", { name: /cap/u })).toBeNull();
		});

		it.each<[string, Partial<WorkspaceLlmUsageReport>, string]>([
			["a cap in force", {}, "Change provider cap"],
			["no cap yet", { ownProviderMonthlyBudgetUsd: undefined }, "Set provider cap"],
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

		it("keeps the table footers in USD, so they alone never call for a rate", async () => {
			await renderPage({ ...uncapped, byDay: twoDaysWithATotalRow, instanceTotalCostUsd: 12.4 });

			const table = screen.getByRole("table", { name: "AI spend by day" });
			const footer = within(table).getByRole("row", { name: /^Total/u });
			expect(footer.textContent).toContain("$12.40");
			expect(footer.textContent).not.toContain("€");
			expect(screen.queryByText(/reference rate published on/u)).toBeNull();
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
