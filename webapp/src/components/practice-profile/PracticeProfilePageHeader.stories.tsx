import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { ARTIFACT_KIND } from "@/lib/artifact-kinds";
import { expectNoPageOverflow } from "@/stories/reflow";

import { PracticeProfilePageHeader } from "./PracticeProfilePageHeader";

const meta = {
	component: PracticeProfilePageHeader,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		latestRun: {
			jobId: "run-2026-09-09",
			at: new Date("2026-09-09T14:10:00"),
			reviewedWork: {
				id: "C01/p1",
				label: "#releases",
				url: "https://example.slack.com/archives/C01/p1",
				kind: ARTIFACT_KIND.conversationThread,
			},
		},
		counts: { STRENGTH: 7, MIXED: 3, DEVELOPING: 2, NO_OPPORTUNITY: 1, NOT_OBSERVED: 3 },
		practiceCount: 16,
		groupCount: 5,
		onSeeAllPractices: fn(),
		isLoading: false,
	},
} satisfies Meta<typeof PracticeProfilePageHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, args, userEvent }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("Practice profile");
		await expect(canvas.getByText("9 September, 2:10 pm")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /^#releases/u })).toBeVisible();
		await expect(canvas.getByText("16 practices in 5 groups")).toBeVisible();

		// Every standing with a count is listed, in the registry's order: what needs attention first,
		// the same order the ring draws its arcs and the tables sort by.
		const items = canvas.getAllByRole("listitem");
		await expect(items.map((item) => item.textContent)).toEqual([
			"2Needs attention",
			"3Mixed",
			"7Going well",
			"1Nothing to report",
			"3Not observed",
		]);

		await userEvent.click(canvas.getByRole("button", { name: "See all practices" }));
		await expect(args.onSeeAllPractices).toHaveBeenCalledOnce();
	},
};

/** A standing at zero drops out of the legend rather than reading "0 Needs attention". */
export const SomeStandingsAbsent: Story = {
	args: {
		counts: { STRENGTH: 9, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 7 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem")).toHaveLength(2);
		await expect(canvas.queryByText("Needs attention")).toBeNull();
	},
};

/** A document title longer than 28 characters is cut in the chip; the full title stays on hover. */
export const LongWorkLabel: Story = {
	args: {
		latestRun: {
			jobId: "run-2026-09-09",
			at: new Date("2026-09-09T14:10:00"),
			reviewedWork: {
				id: "queue-retry-policy",
				label: "Queue retry policy for the notification pipeline",
				url: "https://outline.example.com/doc/queue-retry-policy",
				kind: ARTIFACT_KIND.document,
			},
		},
	},
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: /^Queue retry policy for the n…/u });
		await expect(link).toHaveAttribute("title", "Queue retry policy for the notification pipeline");
	},
};

/** A workspace with no practices: there is no chip, and the box has nothing to count. */
export const Empty: Story = {
	args: {
		latestRun: undefined,
		counts: { STRENGTH: 0, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 0 },
		practiceCount: 0,
		groupCount: 0,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Latest run")).toBeNull();
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
		await expect(canvas.queryByRole("list")).toBeNull();
		await expect(canvas.getByRole("button", { name: "See all practices" })).toBeEnabled();
	},
};

/** Without a handler the button stays where it is, disabled, so the box keeps its shape. */
export const NoSeeAllHandler: Story = {
	args: { onSeeAllPractices: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "See all practices" })).toBeDisabled();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("heading", { level: 1 })).toBeNull();
		await expect(canvas.getByRole("button", { name: "See all practices" })).toBeDisabled();
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
