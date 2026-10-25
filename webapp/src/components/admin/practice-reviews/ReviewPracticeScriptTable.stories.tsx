import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import type { PracticeScriptRun, ReviewPractice } from "@/api/types.gen";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";
import { expectNoPageOverflow, horizontalScrollParentOf } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import { reviewPracticeScripts, workspacePractices } from "./fixtures";
import { ReviewPracticeScriptTable } from "./ReviewPracticeScriptTable";

const PRACTICE_STATUS_OF_RUN = {
	OK: "DONE",
	WITHHELD: "WITHHELD",
	FAILED: "FAILED",
	NOT_REACHED: "FAILED",
	NOT_FINISHED: "FAILED",
} satisfies Record<PracticeScriptRun["status"], ReviewPractice["status"]>;

function scripted(run: PracticeScriptRun): ReviewPractice {
	const practice = workspacePractices.find((entry) => entry.id === run.practiceId);
	if (!practice) {
		throw new Error(`No practice ${run.practiceId} in the fixture catalog`);
	}
	const returned = run.status === "OK";
	const withheld = run.status === "WITHHELD";
	return {
		practiceSlug: practice.slug,
		practiceName: currentDefinition(practice).name,
		practiceVersionId: run.practiceVersionId,
		number: practice.currentVersion.number,
		status: PRACTICE_STATUS_OF_RUN[run.status],
		reason: null,
		itemCounts: {
			listed: 1,
			reviewed: returned ? 1 : 0,
			reused: 0,
			withheld: withheld ? 1 : 0,
			notReviewed: returned || withheld ? 0 : 1,
		},
		results: { met: returned ? 1 : 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 },
		script: run,
	};
}

function scriptRow(table: HTMLElement, practice: string) {
	const row = within(table).getByRole("rowheader", { name: practice }).closest("tr");
	if (row === null) {
		throw new Error(`No row for ${practice}`);
	}
	return within(row);
}

function cellUnder(table: HTMLElement, row: ReturnType<typeof scriptRow>, column: string) {
	const index = within(table)
		.getAllByRole("columnheader")
		.findIndex((head) => head.textContent === column);
	const cell = [...row.getAllByRole("rowheader"), ...row.getAllByRole("cell")][index];
	if (cell === undefined) {
		throw new Error(`No cell under ${column}`);
	}
	return cell;
}

const guideSection = (link: HTMLElement) => new URL(link.getAttribute("href") ?? "").hash;

const meta = {
	component: ReviewPracticeScriptTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		practices: [
			reviewPracticeScripts.fromCode,
			reviewPracticeScripts.withModels,
			reviewPracticeScripts.builtIn,
			reviewPracticeScripts.withheld,
			reviewPracticeScripts.failed,
			reviewPracticeScripts.outOfTime,
			reviewPracticeScripts.notFinished,
		].map(scripted),
	},
	argTypes: { practices: { control: false } },
} satisfies Meta<typeof ReviewPracticeScriptTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const EveryResult: Story = {
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		await expect(
			within(table)
				.getAllByRole("columnheader")
				.map((head) => head.textContent),
		).toEqual(["Practice", "Run", "Review calls", "Script models", "Script calls", "Time"]);
		for (const column of ["Review calls", "Script calls", "Time"]) {
			const head = within(table).getByRole("columnheader", { name: column });
			await expect(getComputedStyle(head).textAlign).toBe("right");
		}
		const rows = within(table).getAllByRole("row").slice(1);
		await expect(
			rows.map((row) => cellUnder(table, within(row), "Run").firstChild?.firstChild?.textContent),
		).toEqual([
			"Returned a candidate observation",
			"Returned a candidate observation",
			"Returned a candidate observation",
			"Withheld",
			"Failed: error",
			"Failed: ran out of time",
			"Did not finish",
		]);
		await expect(canvas.queryByText(/\$|USD/u)).not.toBeInTheDocument();
	},
};

export const ResultFromCode: Story = {
	args: { practices: [scripted(reviewPracticeScripts.fromCode)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Thin controllers");
		await expect(row.getByText("Returned a candidate observation")).toBeVisible();
		await expect(row.getByText("Decision model")).toBeVisible();
		await expect(row.getByText("Cloud")).toHaveClass("sr-only");
		await expect(cellUnder(table, row, "Script calls")).toHaveTextContent(/^12$/u);
		await expect(cellUnder(table, row, "Time")).toHaveTextContent(/^4\.2 s$/u);
		await expect(row.getByRole("rowheader", { name: "Thin controllers" })).toHaveAttribute(
			"scope",
			"row",
		);
		await expect(levelsOpenedBy(row.getByRole("link", { name: "Thin controllers" }))).toStrictEqual(
			["practice:thin-controllers"],
		);
	},
};

export const LongRun: Story = {
	args: { practices: [scripted({ ...reviewPracticeScripts.fromCode, durationMs: 3_725_000 })] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Thin controllers");
		await expect(cellUnder(table, row, "Time")).toHaveTextContent(/^62 min 5 s$/u);
	},
};

export const WithModels: Story = {
	args: { practices: [scripted(reviewPracticeScripts.withModels)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Errors carry their context");
		await expect(row.getByText("Decision model")).toBeVisible();
		await expect(row.getByText("Embedding model")).toBeVisible();
		await expect(cellUnder(table, row, "Script calls")).toHaveTextContent(/^11$/u);
		await expect(cellUnder(table, row, "Time")).toHaveTextContent(/^12\.8 s$/u);
	},
};

export const Withheld: Story = {
	args: { practices: [scripted(reviewPracticeScripts.withheld)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Product language");
		await expect(row.getByText("Withheld")).toBeVisible();
		await expect(cellUnder(table, row, "Script calls")).toHaveTextContent("—none");
		await expect(cellUnder(table, row, "Time")).toHaveTextContent("—none");
		await expect(
			row.queryByRole("link", { name: /for Product language/u }),
		).not.toBeInTheDocument();
	},
};

export const FailedWithError: Story = {
	args: { practices: [scripted(reviewPracticeScripts.failed)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Tests name the behaviour");
		await expect(row.getByText("Failed: error")).toBeVisible();
		await expect(cellUnder(table, row, "Time")).toHaveTextContent(/^< 0\.1 s$/u);
		const error = row.getByText(/^TypeError: change\.files is not iterable/u);
		await expect(error).toBeVisible();
		await expect(error.closest("td")).toBe(cellUnder(table, row, "Run"));
		const edit = row.getByRole("link", { name: "Edit the script for Tests name the behaviour" });
		await expect(edit.getAttribute("href")).toMatch(/^\/w\/demo\/admin\/practices\?/u);
		await expect(levelsOpenedBy(edit)).toStrictEqual(["practice-edit:tests-name-the-behaviour"]);
	},
};

export const NotFinished: Story = {
	args: { practices: [scripted(reviewPracticeScripts.notFinished)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Decisions are written down");
		await expect(row.getByText("Did not finish")).toBeVisible();
		await expect(cellUnder(table, row, "Time")).toHaveTextContent("—none");
		const guide = row.getByRole("link", {
			name: /^Why scripts stop early for Decisions are written down/u,
		});
		await expect(guideSection(guide)).toBe("#the-trace-line");
	},
};

export const NotReached: Story = {
	args: { practices: [scripted(reviewPracticeScripts.notReached)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Decisions are written down");
		await expect(row.getByText("Not reached")).toBeVisible();
		await expect(cellUnder(table, row, "Review calls")).toHaveTextContent("—none");
		await expect(cellUnder(table, row, "Script calls")).toHaveTextContent("—none");
		await expect(cellUnder(table, row, "Time")).toHaveTextContent("—none");
		const guide = row.getByRole("link", {
			name: /^Why scripts stop early for Decisions are written down/u,
		});
		await expect(guideSection(guide)).toBe("#the-trace-line");
	},
};

/** The evidence check refused its results; this is not a format failure, and its review calls are known. */
export const EvidenceNotRead: Story = {
	args: { practices: [scripted(reviewPracticeScripts.evidenceNotRead)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "The change explains itself");
		await expect(row.getByText("Failed: evidence not read")).toBeVisible();
		await expect(row.queryByText(/wrong format/u)).not.toBeInTheDocument();
		await expect(cellUnder(table, row, "Review calls")).toHaveTextContent(/^6$/u);
		await expect(cellUnder(table, row, "Script models")).toHaveTextContent("—none");
		await expect(row.queryByRole("link", { name: /Edit the script/u })).not.toBeInTheDocument();
	},
};

/** A run recorded before review calls were counted says so, rather than none. */
export const ReviewCallsNotRecorded: Story = {
	args: { practices: [scripted(reviewPracticeScripts.builtInUnrecorded)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "The change explains itself");
		await expect(row.getByText("Failed: no accepted candidate observation")).toBeVisible();
		await expect(cellUnder(table, row, "Review calls")).toHaveTextContent(/^Not recorded$/u);
	},
};

export const BuiltInReview: Story = {
	args: { practices: [scripted(reviewPracticeScripts.builtIn)] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "The change explains itself");
		await expect(row.getByText("Returned a candidate observation")).toBeVisible();
		await expect(cellUnder(table, row, "Script models")).toHaveTextContent("—none");
		await expect(cellUnder(table, row, "Review calls")).toHaveTextContent(/^4$/u);
		await expect(row.queryByRole("link", { name: /Edit the script/u })).not.toBeInTheDocument();
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		await expect(table).toBeVisible();
		await expectNoPageOverflow();
		const error = within(table).getByText(/^TypeError:/u);
		const cell = error.closest("td");
		await expect(cell).not.toBeNull();
		await expect(error.getBoundingClientRect().right).toBeLessThanOrEqual(
			cell?.getBoundingClientRect().right ?? 0,
		);
		const frame = horizontalScrollParentOf(table);
		await expect(frame.scrollLeft).toBe(0);
		await expect(cell?.getBoundingClientRect().right ?? Infinity).toBeLessThanOrEqual(
			frame.getBoundingClientRect().right,
		);
		await expect(error.getBoundingClientRect().height).toBeGreaterThan(
			Number.parseFloat(getComputedStyle(error).lineHeight) * 1.5,
		);
	},
};
