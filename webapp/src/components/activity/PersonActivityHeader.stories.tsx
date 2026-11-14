import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { ada } from "@/stories/activity-story-data";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";

import { PersonActivityHeader } from "./PersonActivityHeader";

/**
 * The profile identity at the top of a person's activity level. The login is known before the
 * person is, so the header names the profile from the login alone until the person arrives.
 */
const meta = {
	component: PersonActivityHeader,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Activity", depth: 0 }], onClose: fn() },
		login: "ada",
		person: ada,
		providerType: "GITHUB",
	},
	argTypes: { path: { control: false }, person: { control: false } },
	render: (args) => (
		<InLevelStack entry={{ kind: "person", id: "ada" }} path={args.path}>
			{(level) => <PersonActivityHeader {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof PersonActivityHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveTextContent("Ada Lovelace");
		panel.getByText("ada on GitHub");
	},
};

/** The person is on its way: the name's shape stands in, and the login still names the drawer. */
export const Loading: Story = {
	args: { person: undefined, loading: true },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(within(panel).getByRole("heading", { level: 2 })).toHaveTextContent("ada");
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
	},
};

/** A person the provider never returned: the login stands in for the name, with no placeholder. */
export const PersonNotFound: Story = {
	args: { person: undefined },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(within(panel).getByRole("heading", { level: 2 })).toHaveTextContent("ada");
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBe(0);
	},
};
