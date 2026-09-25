import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { Tabs, TabsContent } from "@/components/ui/tabs";
import { expectNoPageOverflow } from "@/stories/reflow";

import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsSkeleton,
	PracticeTabsTrigger,
} from "./practice-tabs";

const TABS = [
	{ value: "newest", label: "Newest", count: 2 },
	{ value: "open", label: "Open", count: 6 },
	{ value: "resolved", label: "Resolved", count: 3 },
	{ value: "all", label: "All", count: 9 },
];

const meta = {
	component: PracticeTabsRail,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	// The rail is what a caller lays out; the tabs and their counts inside it are the story's.
	render: (args) => (
		<Tabs defaultValue="newest" className="gap-4">
			<PracticeTabsRail {...args}>
				<PracticeTabsList aria-label="Feedback">
					{TABS.map((tab) => (
						<PracticeTabsTrigger key={tab.value} value={tab.value} count={tab.count}>
							{tab.label}
						</PracticeTabsTrigger>
					))}
				</PracticeTabsList>
				<span className="pb-2 text-sm text-muted-foreground">Newest first</span>
			</PracticeTabsRail>
			{TABS.map((tab) => (
				<TabsContent key={tab.value} value={tab.value}>
					<p className="text-sm text-muted-foreground">
						{tab.label}: {tab.count}
					</p>
				</TabsContent>
			))}
		</Tabs>
	),
} satisfies Meta<typeof PracticeTabsRail>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The rail with counts: each tab's accessible name is its words and its count, with a space
 * between.
 */
export const WithCounts: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("tab").map((tab) => tab.textContent)).toStrictEqual([
			"Newest 2",
			"Open 6",
			"Resolved 3",
			"All 9",
		]);
		await expect(canvas.getByRole("tab", { name: "Newest 2" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
	},
};

/** While the counts load the rail holds tab-shaped blanks, and no tab is a control yet. */
export const Skeleton: Story = {
	render: () => <PracticeTabsSkeleton tabs={4} />,
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("tab")).toBeNull();
	},
};

/** At 320px the tabs wrap under the note beside them rather than widening the page. */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
