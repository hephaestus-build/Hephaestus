import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { InlineLink } from "./InlineLink";

const meta = {
	component: InlineLink,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { children: "Scope the change to one concern" },
	render: (args) => (
		<p className="max-w-md text-sm">
			Keep doing this on <InlineLink {...args} /> and the reviewer reads the diff already knowing
			what to look for.
		</p>
	),
} satisfies Meta<typeof InlineLink>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A name that opens something on this page is a button in the running text. */
export const OpensALevel: Story = {
	args: { onClick: fn() },
	play: async ({ args, canvas }) => {
		const link = canvas.getByRole("button", { name: "Scope the change to one concern" });
		// The one link rule: plain at rest, a solid underline in mentor blue on hover.
		await expect(link).not.toHaveClass("underline");
		await expect(link).not.toHaveClass("decoration-dashed");
		await expect(link).toHaveClass("hover:underline");
		await expect(link).toHaveClass("hover:text-mentor");
		await userEvent.click(link);
		await expect(args.onClick).toHaveBeenCalledOnce();
	},
};

/** An address on this site: a plain link, nothing added. */
export const Internal: Story = {
	args: { href: "/w/hephaestus/practice-profile", children: "your practice profile" },
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: "your practice profile" });
		await expect(link).not.toHaveAttribute("target");
		await expect(link).not.toHaveClass("underline");
		await expect(link).toHaveClass("hover:underline");
	},
};

/**
 * The provider's page opens in a new tab, and the link says so to a screen reader as well as with
 * the icon.
 */
export const External: Story = {
	args: { href: "https://github.com/ls1intum/Hephaestus/pull/22", external: true, children: "#22" },
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: "#22 (opens in a new tab)" });
		await expect(link).toHaveAttribute("target", "_blank");
		await expect(link).toHaveAttribute("rel", "noopener noreferrer");
	},
};

/**
 * A reference with no address is a word in the running text: no hover promising a press it cannot
 * answer. A url that arrives empty is no address either, `external` or not — an empty string must
 * not buy the outbound icon and the "(opens in a new tab)" sentence for a word that opens nothing.
 */
export const NoAddress: Story = {
	args: { children: "!425" },
	render: (args) => (
		<p className="max-w-md text-sm">
			Neither <InlineLink {...args} /> nor{" "}
			<InlineLink {...args} href="" external>
				#318
			</InlineLink>{" "}
			carries an address.
		</p>
	),
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.queryByText("(opens in a new tab)")).toBeNull();
		for (const label of ["!425", "#318"]) {
			const word = canvas.getByText(label);
			await expect(word.tagName).toBe("SPAN");
			await expect(word).not.toHaveAttribute("target");
			await expect(word).not.toHaveClass("underline");
			await expect(word).not.toHaveClass("hover:underline");
		}
	},
};
