import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, userEvent, waitFor } from "storybook/test";

import { GroupPill } from "@/components/admin/practice-editor/GroupPill";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";

import { DetailDrawerHeader } from "./DetailDrawerHeader";

const meta = {
	component: DetailDrawerHeader,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		children: (
			<>
				<GroupPill size="lg" slug="review-ready-work" name="Review-ready work" />
				<div className="min-w-0 flex-1 space-y-0.5">
					<DrawerTitle>Describe what changed and why</DrawerTitle>
					<DrawerDescription>Pull request</DrawerDescription>
				</div>
			</>
		),
	},
	argTypes: { children: { control: false } },
	render: (args) => (
		<Stateful initial={[{ kind: "practice", id: "describe-what-and-why" }]}>
			{(stack, setStack) => (
				<DetailDrawerStack stack={stack} onClose={(depth) => setStack(stack.slice(0, depth))}>
					{() => (
						<>
							<DetailDrawerHeader {...args} />
							<DrawerBody>
								<p className="text-sm text-muted-foreground">Body.</p>
							</DrawerBody>
						</>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof DetailDrawerHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const TopLevel: Story = {
	play: async () => {
		// At the top of a stack, dismissing returns to the page, so the control says Close.
		const close = await screen.findByRole("button", { name: "Close" });
		await expectSettledVisible(close);
		await expect(screen.queryByRole("button", { name: "Back" })).not.toBeInTheDocument();
		await userEvent.click(close);
		// The panel leaving is the claim; the page behind it belongs to the decorator, not to this
		// component.
		await waitFor(async () =>
			expect(document.querySelectorAll('[data-slot="drawer-popup"]')).toHaveLength(0),
		);
	},
};

export const Nested: Story = {
	args: { nested: true },
	play: async () => {
		// Below the top, dismissing returns to the drawer behind, so the control says Back.
		await expectSettledVisible(await screen.findByRole("button", { name: "Back" }));
		await expect(screen.queryByRole("button", { name: "Close" })).not.toBeInTheDocument();
	},
};
