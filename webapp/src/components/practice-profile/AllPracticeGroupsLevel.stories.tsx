import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { DEFAULT_PRACTICE_GROUP_SORT } from "@/components/practice-vocabulary/practice-group-list-order";
import { withPageBehind, withProvider } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	practicesByGroup,
} from "@/stories/practice-profile-story-mock-data";

import { AllPracticeGroupsLevel } from "./AllPracticeGroupsLevel";
import { composeOverview } from "./compose-overview";
import { ALL_PRACTICE_GROUPS_LEVEL } from "./practice-profile-search";

/**
 * Every practice group as a level over the practice profile. The table's own states are drawn
 * under the level's header, which stays whatever the table shows.
 */
const meta = {
	component: AllPracticeGroupsLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind, withProvider("GITHUB")],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Practice profile", depth: 0 }], onClose: fn() },
		groups,
		standings: groupStandings,
		practicesByGroup,
		sentences: composeOverview(OVERVIEW_FIXTURE).groupSentences,
		sort: DEFAULT_PRACTICE_GROUP_SORT,
		onSortChange: fn(),
		onOpenGroup: fn(),
		onOpenPractice: fn(),
		state: { status: "ready" },
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={ALL_PRACTICE_GROUPS_LEVEL} path={args.path}>
			{(level) => <AllPracticeGroupsLevel {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof AllPracticeGroupsLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

const TABLE = { name: "All practice groups" } as const;

export const Default: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveTextContent("All practice groups");
		await expect(panel.getByRole("table", TABLE)).not.toHaveAttribute("aria-busy");
	},
};

/** The header stands, and the table draws its own shape while the standings load. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveTextContent("All practice groups");
		await expect(panel.getByRole("table", TABLE)).toHaveAttribute("aria-busy", "true");
	},
};

export const LoadFailed: Story = {
	args: {
		groups: [],
		state: { status: "error", error: { status: 503 }, onRetry: fn() },
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("We could not load your practice groups");
		await userEvent.click(panel.getByRole("button", { name: "Retry" }));
		if (args.state.status !== "error") {
			throw new Error("This story renders the error state.");
		}
		await expect(args.state.onRetry).toHaveBeenCalledTimes(1);
	},
};
