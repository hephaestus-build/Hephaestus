import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { expectGenuinelyDisabled } from "@/test/controls";

import { UserViewDialog } from "./UserViewDialog";

const meta = {
	component: UserViewDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { name: "Sam", onClose: fn(), onConfirm: fn() },
} satisfies Meta<typeof UserViewDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const EmptyReason: Story = {
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "View as user" }));
		const reason = screen.getByRole("textbox", { name: "Reason for access" });
		const description = screen.getByText(/Describe the support need/u);
		await expect(reason.getAttribute("aria-describedby")?.split(" ")).toContain(description.id);
	},
};

export const Opening: Story = {
	args: { isPending: true },
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Opening…" }));
	},
};

export const Failed: Story = {
	args: { error: "User view audit is unavailable" },
	play: async () => {
		await expect(await screen.findByRole("alert")).toHaveTextContent(
			"User view audit is unavailable",
		);
	},
};

export const SubmitsTrimmedReason: Story = {
	play: async ({ args }) => {
		await userEvent.type(
			await screen.findByRole("textbox", { name: "Reason for access" }),
			"  Investigate missing practice feedback  ",
		);
		await userEvent.click(screen.getByRole("button", { name: "View as user" }));
		await expect(args.onConfirm).toHaveBeenCalledWith("Investigate missing practice feedback");
	},
};
