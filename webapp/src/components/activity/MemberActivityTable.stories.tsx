import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { LARGE_ROSTER, MEMBERS, readyMembers } from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow, expectTablesScrollInPlace } from "@/stories/reflow";

import { MemberActivityTable } from "./MemberActivityTable";

/**
 * Members by name, each with their own chips. There is no count column to sort by, no total across
 * kinds and no bar on a shared scale, so the table cannot be bent into a ranking.
 */
const meta = {
	component: MemberActivityTable,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyMembers(MEMBERS),
		providerType: "GITHUB",
	},
} satisfies Meta<typeof MemberActivityTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("5 members · 3 active")).toBeVisible();
		const rows = canvas.getAllByRole("row").slice(1);
		// By name, though Bob has done the most.
		await expect(rows.map((row) => row.querySelector("a")?.textContent)).toEqual([
			"Ada Lovelace",
			"Bob Brenner",
			"Chen Wei",
			"Dana Okafor",
			"Élodie Brière",
		]);
		await expect(canvas.getByRole("link", { name: "Bob Brenner" })).toHaveAttribute(
			"href",
			expect.stringContaining("member%3Abob"),
		);
		// A quiet member reads as nothing, not as zeros.
		await expect(rows[2]).toHaveTextContent(/Chen Weichen(?:—None){4}$/u);
	},
};

/** 250 members, most of them quiet: the first 50 by name, a search, and the rest one press away. */
export const LargeWorkspace: Story = {
	args: { state: readyMembers(LARGE_ROSTER) },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByText(/^250 members · \d+ active$/u)).toBeVisible();
		await expect(canvas.getAllByRole("row")).toHaveLength(51);
		await userEvent.click(canvas.getByRole("button", { name: "Show all 250" }));
		await expect(canvas.getAllByRole("row")).toHaveLength(251);
		await userEvent.type(canvas.getByRole("searchbox", { name: "Search members" }), "zoe");
		// While a search narrows the table, the caption says how far.
		await expect(canvas.getByText("9 of 250 members")).toBeVisible();
		await expect(canvas.getAllByRole("row")).toHaveLength(10);
		for (const row of canvas.getAllByRole("row").slice(1)) {
			await expect(row).toHaveTextContent(/zoe/iu);
		}
	},
};

export const NoResults: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.type(canvas.getByRole("searchbox", { name: "Search members" }), "nobody");
		await expect(canvas.getByText("No results found")).toBeVisible();
		await expect(canvas.getByText("0 of 5 members")).toBeVisible();
		await expect(canvas.getByText("Edit your search and try again.")).toBeVisible();
	},
};

/** A name typed without its accents, or in another case, still finds it. */
export const SearchIgnoresAccents: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.type(
			canvas.getByRole("searchbox", { name: "Search members" }),
			"ELODIE BRIERE",
		);
		await expect(canvas.getByText("1 of 5 members")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Élodie Brière" })).toBeVisible();
	},
};

/** While another range loads, the previous range's rows stay, dimmed and marked busy. */
export const Stale: Story = {
	args: { state: { status: "ready", members: MEMBERS, stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "Members" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.getByRole("link", { name: "Ada Lovelace" })).toBeVisible();
	},
};

export const NoMembers: Story = {
	args: { state: readyMembers([]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No members here")).toBeVisible();
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: "Merge requests" })).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoPageOverflow();
		await expectTablesScrollInPlace(canvasElement, { expectOverflow: true });
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "Members" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};
