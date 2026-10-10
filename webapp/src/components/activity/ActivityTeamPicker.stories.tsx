import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { TEAMS } from "@/stories/activity-story-data";
import { settledPopup } from "@/stories/overlay";

import { ActivityTeamPicker } from "./ActivityTeamPicker";

const meta = {
	component: ActivityTeamPicker,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { teams: TEAMS, value: undefined, onChange: fn() },
} satisfies Meta<typeof ActivityTeamPicker>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Teams by their paths, found by typing, and Everyone to go back. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Team: Everyone" }));
		const popup = within(await settledPopup());
		await userEvent.type(popup.getByRole("combobox", { name: "Search teams" }), "pay");
		await expect(popup.getAllByRole("option").map((option) => option.textContent)).toStrictEqual([
			"Platform / Payments",
		]);
		await userEvent.click(screen.getByRole("option", { name: "Platform / Payments" }));
		await expect(args.onChange).toHaveBeenCalledWith("payments");
	},
};

export const OneTeam: Story = {
	args: { value: "payments" },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Team: Platform / Payments" }));
		await userEvent.click(within(await settledPopup()).getByRole("option", { name: "Everyone" }));
		await expect(args.onChange).toHaveBeenCalledWith(undefined);
	},
};

/** Before the teams arrive, the address's slug stands in for the name. */
export const Loading: Story = {
	args: { teams: undefined, value: "payments" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Team: payments" })).toBeVisible();
	},
};

/** A workspace with no teams has nothing to pick, so there is no picker. */
export const NoTeams: Story = {
	args: { teams: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("combobox")).not.toBeInTheDocument();
	},
};
