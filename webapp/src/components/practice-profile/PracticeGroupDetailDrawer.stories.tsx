import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { DEFAULT_PRACTICE_GROUP_SORT } from "@/components/practice-vocabulary/practice-group-list-order";
import { EMPTY_REVIEW_FEED } from "@/components/profile/reviews";
import { withPageBehind, withProvider } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { detailPractices } from "@/stories/practice-detail-story-mock-data";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	packagingGroup,
	practicesByGroup,
} from "@/stories/practice-profile-story-mock-data";

import { composeOverview } from "./compose-overview";
import { PracticeGroupDetailDrawer } from "./PracticeGroupDetailDrawer";

/**
 * The drawer the Practice profile opens from its `detail` stack. The page's load state reaches
 * every level it draws, so a level never shows a standing the page has not got.
 */
const meta = {
	component: PracticeGroupDetailDrawer,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind, withProvider("GITHUB")],
	tags: ["autodocs"],
	args: {
		detail: {
			practices: detailPractices,
			feed: { status: "loading" },
			guidance: { status: "loading" },
		},
		detailStack: [{ kind: "practice-group", id: packagingGroup.slug }],
		allPracticeGroups: {
			practicesByGroup,
			sentences: composeOverview(OVERVIEW_FIXTURE).groupSentences,
			sort: DEFAULT_PRACTICE_GROUP_SORT,
			onSortChange: fn(),
			onOpenGroup: fn(),
			onOpenPractice: fn(),
		},
		onClose: fn(),
		onOpenPractice: fn(),
		groups,
		groupStandings,
		state: { status: "ready" },
		practiceTab: "feedback",
		skeletonRows: 4,
		reviews: {
			list: {
				feed: EMPTY_REVIEW_FEED,
				onOpenReview: fn(),
				onKindChange: fn(),
				onSinceChange: fn(),
			},
			open: { state: { status: "loading" } },
		},
		onSelectionChange: fn(),
	},
	argTypes: {
		detail: { control: false },
		detailStack: { control: false },
		allPracticeGroups: { control: false },
		reviews: { control: false },
	},
} satisfies Meta<typeof PracticeGroupDetailDrawer>;

export default meta;
type Story = StoryObj<typeof meta>;

const PRACTICES = { name: "Practices in this group" } as const;

export const Default: Story = {
	play: async () => {
		const table = await screen.findByRole("table", PRACTICES);
		await expectSettledVisible(table);
		await expect(table).not.toHaveAttribute("aria-busy");
	},
};

/** The page's standings are on their way, so the open level draws its shape and claims nothing. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		const table = await screen.findByRole("table", PRACTICES);
		await expectSettledVisible(table);
		await expect(table).toHaveAttribute("aria-busy", "true");
	},
};

export const LoadFailed: Story = {
	args: { state: { status: "error", error: { status: 503 }, onRetry: fn() } },
	play: async ({ args }) => {
		await expectSettledVisible(
			await screen.findByText("We could not load your standing for Packaging work for review"),
		);
		await userEvent.click(screen.getByRole("button", { name: "Retry" }));
		if (args.state.status !== "error") {
			throw new Error("This story renders the error state.");
		}
		await expect(args.state.onRetry).toHaveBeenCalledTimes(1);
	},
};
