import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ACTED_ON_RECORD, DISMISSED_RECORD, OPEN_RECORD } from "./decision-fixtures";
import { TeamDecisionList } from "./TeamDecisionList";

const meta = {
	component: TeamDecisionList,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", records: [OPEN_RECORD, ACTED_ON_RECORD, DISMISSED_RECORD] },
		questionLink: () => <a href="#decision-thread" />,
	},
	decorators: [
		(Story) => (
			<div className="max-w-xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof TeamDecisionList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One current record per problem, newest first, with who can see them said before anyone records one. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Every current member of the team sees these records and who recorded them.",
			),
		).toBeVisible();
		const records = canvas.getAllByRole("article");
		await expect(records).toHaveLength(2);
		await expect(records[0]).toHaveAccessibleName(/Ideate phase/u);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading", rows: 2 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Team decisions" })).toBeVisible();
		await expect(canvas.queryByRole("article")).toBeNull();
	},
};

export const Error: Story = {
	args: { state: { status: "error", error: new globalThis.Error("Network error") } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Team decisions could not load")).toBeVisible();
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", records: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "No team decisions yet" })).toBeVisible();
		await expect(canvas.getByText(/only a member records it/u)).toBeVisible();
	},
};
