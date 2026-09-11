import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor, within } from "storybook/test";
import { StatefulPatch } from "@/stories/stateful";
import { expectNoPageOverflow } from "@/test/reflow";
import type { ReviewSectionId } from "./review-sections";
import { ReviewPage } from "./ReviewPage";

const sectionBody = (id: ReviewSectionId) => (
	<p className="rounded-md border border-dashed p-6 text-muted-foreground text-sm">
		Section body: {id}
	</p>
);

const sections = {
	"how-much": sectionBody("how-much"),
	"when-and-where": sectionBody("when-and-where"),
	"past-work": sectionBody("past-work"),
};

const readyBinding = {
	dataHandlingTier: "IN_HOUSE",
	purpose: "PRACTICE_REVIEW",
	enabled: true,
	ready: true,
} as const;

const meta = {
	title: "Workspace admin/Practices/Review/Overview",
	component: ReviewPage,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 1440] },
		viewport: { defaultViewport: "reflow" },
	},
	tags: ["autodocs"],
	args: {
		section: "how-much",
		onSectionChange: fn(),
		running: {
			enabled: true,
			model: { status: "ready", binding: readyBinding },
		},
		sections,
	},
	render: (args) => (
		<StatefulPatch initial={{ section: args.section }}>
			{(view, patch) => (
				<ReviewPage
					{...args}
					section={view.section}
					onSectionChange={(section) => {
						args.onSectionChange(section);
						patch({ section });
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ReviewPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const HowMuch: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("tab", { name: "How much" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(canvas.getByText("Section body: how-much")).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const WhenAndWhere: Story = {
	args: { section: "when-and-where" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Section body: when-and-where")).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const PastWork: Story = {
	args: { section: "past-work" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Section body: past-work")).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const NoModelIsReady: Story = {
	args: { running: { enabled: true, model: { status: "ready" } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Reviews can't start");
	},
};

export const StateNotKnownYet: Story = {
	args: { running: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("status")).not.toBeInTheDocument();
		await expect(canvas.getByRole("tablist")).toBeVisible();
	},
};

export const SectionsAreRealTabs: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const list = canvas.getByRole("tablist");
		await expect(
			within(list)
				.getAllByRole("tab")
				.map((tab) => tab.textContent),
		).toEqual(["How much", "When and where", "Past work"]);
		await expect(canvas.getAllByRole("tabpanel")).toHaveLength(1);
		await expect(canvas.queryByText("Section body: when-and-where")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Section body: past-work")).not.toBeInTheDocument();

		await userEvent.click(canvas.getByRole("tab", { name: "When and where" }));
		await expect(args.onSectionChange).toHaveBeenCalledWith("when-and-where");
		await expect(await canvas.findByText("Section body: when-and-where")).toBeVisible();
		await waitFor(() =>
			expect(canvas.queryByText("Section body: how-much")).not.toBeInTheDocument(),
		);
	},
};
