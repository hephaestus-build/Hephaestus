import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { PEOPLE } from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ActivityHighlights } from "./ActivityHighlights";

const byLogin = (login: string) => PEOPLE.filter(({ person }) => person.login === login);
const [ada] = byLogin("ada");
const [bob] = byLogin("bob");
const [elodie] = byLogin("elodie");

const meta = {
	component: ActivityHighlights,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		firstContributors: elodie ? [elodie] : [],
		mostPeopleHelped: ada ? [ada] : [],
	},
} satisfies Meta<typeof ActivityHighlights>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Contributed for the first time in this range.")).toBeVisible();
		await expect(canvas.getByText("Reviewed the work of 9 people.")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Élodie Brière" })).toHaveAttribute(
			"href",
			expect.stringContaining("detail=person:elodie"),
		);
	},
};

/** A tie for most people helped names everyone in it. */
export const Tie: Story = {
	args: { firstContributors: [], mostPeopleHelped: [ada, bob].flatMap((p) => (p ? [p] : [])) },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("First contributions")).not.toBeInTheDocument();
		await expect(canvas.getAllByRole("link")).toHaveLength(2);
	},
};

/** Nobody named for either: nothing renders, so the page shows no empty cards. */
export const Empty: Story = {
	args: { firstContributors: [], mostPeopleHelped: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};
