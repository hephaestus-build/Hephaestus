import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor } from "storybook/test";

import { mockGroups } from "@/components/admin/practices/fixtures";
import { expectSettledVisible } from "@/stories/overlay";
import { expectGenuinelyDisabled } from "@/test/controls";

import { GroupDetailsDialog } from "./GroupDetailsDialog";

const [reviewReadyGroup] = mockGroups;
if (!reviewReadyGroup) {
	throw new Error("The shared group fixtures no longer hold a group to edit");
}

const meta = {
	component: GroupDetailsDialog,
	parameters: { layout: "centered" },
	args: {
		group: null,
		open: true,
		pending: false,
		onOpenChange: fn(),
		onSubmit: fn(async () => true),
	},
	argTypes: { group: { control: false } },
	tags: ["autodocs"],
} satisfies Meta<typeof GroupDetailsDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Creating: Story = {
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Create group" }));
		await userEvent.type(screen.getByLabelText("Name"), "Documentation");
		await userEvent.click(screen.getByRole("button", { name: "Create" }));
		await expect(args.onSubmit).toHaveBeenCalledWith({
			name: "Documentation",
			icon: null,
			color: null,
		});
	},
};

export const Renaming: Story = {
	args: { group: reviewReadyGroup },
	play: async ({ args }) => {
		const field = await screen.findByLabelText("Name");
		await expectSettledVisible(field);
		await expect(screen.getByRole("heading", { name: "Edit group" })).toBeVisible();
		await userEvent.clear(field);
		await userEvent.type(field, "Review-ready work");
		await userEvent.click(screen.getByRole("button", { name: "Save" }));
		await expect(args.onSubmit).toHaveBeenCalledWith({
			name: "Review-ready work",
			icon: reviewReadyGroup.icon ?? null,
			color: reviewReadyGroup.color ?? null,
		});
	},
};

export const UnchangedDetailsJustClose: Story = {
	args: { group: reviewReadyGroup },
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByLabelText("Name"));
		await userEvent.click(screen.getByRole("button", { name: "Save" }));
		await expect(args.onSubmit).not.toHaveBeenCalled();
		await expect(args.onOpenChange).toHaveBeenCalledWith(false);
	},
};

export const EmptyNameCannotBeSubmitted: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByLabelText("Name"));
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Create" }));
	},
};

export const Pending: Story = {
	args: { pending: true },
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Creating…" }));
	},
};

export const SavingAnEdit: Story = {
	args: { group: reviewReadyGroup, pending: true },
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Saving…" }));
	},
};

export const ChoosingAnIcon: Story = {
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByLabelText("Name"));
		await userEvent.type(screen.getByLabelText("Name"), "Documentation");
		await userEvent.click(
			screen.getByRole("button", { name: "Edit the icon and color for Documentation" }),
		);
		await userEvent.click(await screen.findByRole("button", { name: "Shield alert" }));
		await userEvent.keyboard("{Escape}");
		await waitFor(() =>
			expect(screen.queryByRole("textbox", { name: "Search icons" })).not.toBeInTheDocument(),
		);
		await userEvent.click(screen.getByRole("button", { name: "Create" }));
		await expect(args.onSubmit).toHaveBeenCalledWith(
			expect.objectContaining({ name: "Documentation", icon: "ShieldAlert" }),
		);
	},
};
