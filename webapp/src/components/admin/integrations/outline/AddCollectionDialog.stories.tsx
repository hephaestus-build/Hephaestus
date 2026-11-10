import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { OutlineCollectionCandidate } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { sleep } from "@/test/async";

import { AddCollectionDialog } from "./AddCollectionDialog";

const meta = {
	component: AddCollectionDialog,
	parameters: {
		layout: "centered",
		// The dialog is a modal overlay: each story gets its own frame on the Docs page.
		docs: { story: { inline: false, height: "600px" } },
	},
	tags: ["autodocs"],
	args: {
		candidates: { isLoading: true, onRetry: fn() },
		open: true,
		onOpenChange: fn(),
		onRegister: fn(),
	},
} satisfies Meta<typeof AddCollectionDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

const candidates: OutlineCollectionCandidate[] = [
	{
		collectionId: "col-engineering",
		name: "Engineering",
		urlId: "engineering-4nZ3x",
		color: "#4E5C6E",
		alreadyMirrored: true,
	},
	{
		collectionId: "col-product",
		name: "Product",
		urlId: "product-2mR8v",
		icon: "🧭",
		alreadyMirrored: false,
	},
	{
		collectionId: "col-design",
		name: "Design System",
		urlId: "design-5tK7q",
		alreadyMirrored: false,
	},
	{
		collectionId: "col-research",
		name: "Research Notes",
		urlId: "research-8pL4m",
		alreadyMirrored: false,
	},
];

/** The candidates as the route hands them over once Outline has answered. */
const read = (items: readonly OutlineCollectionCandidate[]) => ({
	items,
	isLoading: false,
	onRetry: fn(),
});

export const Loading: Story = {};

export const ProbeFailed: Story = {
	args: {
		candidates: {
			isLoading: false,
			error: {
				type: "about:blank",
				title: "Bad Gateway",
				status: 502,
				detail: "Outline did not respond to collections.list.",
			},
			onRetry: fn(),
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(await within(dialog).findByText(/outline did not respond/iu));
		within(dialog).getByRole("button", { name: /^retry$/iu });
	},
};

export const NoVisibleCollections: Story = {
	args: { candidates: read([]) },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(
			await within(dialog).findByText(/this token cannot see any collections/iu),
		);
		within(dialog).getByText(/add the bot user/iu);
	},
};

export const PopulatedSearchable: Story = {
	args: { candidates: read(candidates) },
	play: async () => {
		const dialog = await screen.findByRole("dialog");

		const mirrored = await within(dialog).findByRole("option", { name: /engineering/iu });
		await expect(mirrored).toHaveAttribute("data-disabled");
		within(dialog).getByText(/already mirrored/iu);

		await userEvent.type(within(dialog).getByRole("combobox"), "design");
		within(dialog).getByText("Design System");
		await expect(within(dialog).queryByText("Research Notes")).not.toBeInTheDocument();

		await userEvent.click(within(dialog).getByRole("option", { name: /design system/iu }));
		await expect(within(dialog).getByRole("button", { name: /add 1 collection/iu })).toBeEnabled();
	},
};

export const MultiSelect: Story = {
	args: { candidates: read(candidates) },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expect(await within(dialog).findByRole("listbox")).toHaveAttribute(
			"aria-multiselectable",
			"true",
		);

		await userEvent.click(await within(dialog).findByRole("option", { name: /product/iu }));
		await userEvent.click(within(dialog).getByRole("option", { name: /design system/iu }));

		await expect(within(dialog).getByRole("option", { name: /product/iu })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(within(dialog).getByRole("button", { name: /add 2 collections/iu })).toBeEnabled();

		await userEvent.type(within(dialog).getByRole("combobox"), "research");
		await expect(within(dialog).getByRole("button", { name: /add 2 collections/iu })).toBeEnabled();

		await userEvent.clear(within(dialog).getByRole("combobox"));
		await userEvent.click(await within(dialog).findByRole("option", { name: /product/iu }));
		await expect(within(dialog).getByRole("button", { name: /add 1 collection/iu })).toBeEnabled();
	},
};

export const KeyboardNavigation: Story = {
	args: { candidates: read(candidates) },
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		const search = await within(dialog).findByRole("combobox");
		await waitFor(async () => expect(search).toHaveFocus());

		await userEvent.keyboard("{ArrowDown}");
		const engineering = within(dialog).getByRole("option", { name: /engineering/iu });
		await waitFor(async () =>
			expect(search).toHaveAttribute("aria-activedescendant", engineering.id),
		);

		const product = within(dialog).getByRole("option", { name: /product/iu });
		await userEvent.keyboard("{ArrowDown}");
		await waitFor(async () => expect(search).toHaveAttribute("aria-activedescendant", product.id));

		await userEvent.keyboard("{ArrowUp}");
		await waitFor(async () =>
			expect(search).toHaveAttribute("aria-activedescendant", engineering.id),
		);

		await userEvent.keyboard("{Enter}");
		await expect(
			within(dialog).getByRole("button", { name: /^add collections$/iu }),
		).toBeDisabled();

		await userEvent.keyboard("{ArrowDown}{Enter}");
		await waitFor(async () => expect(product).toHaveAttribute("aria-selected", "true"));
		await expect(args.onRegister).not.toHaveBeenCalled();

		await userEvent.keyboard("{Enter}");
		await waitFor(async () => expect(product).toHaveAttribute("aria-selected", "false"));
	},
};

export const EmptySearchResult: Story = {
	args: { candidates: read(candidates) },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await userEvent.type(await within(dialog).findByRole("combobox"), "nothing-matches-this");
		await waitFor(() => expect(within(dialog).getByText(/no collections match your search/iu)));
		await expect(within(dialog).queryByRole("option")).not.toBeInTheDocument();
	},
};

export const AllAlreadyMirrored: Story = {
	args: { candidates: read(candidates.map((c) => ({ ...c, alreadyMirrored: true }))) },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(
			await within(dialog).findByText(/every visible collection is already mirrored/iu),
		);
		await expect(within(dialog).queryByRole("listbox")).not.toBeInTheDocument();
	},
};

export const RegisteringSequentially: Story = {
	args: {
		candidates: read(candidates),
		onRegister: fn(async () => sleep(400)),
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await userEvent.click(await within(dialog).findByRole("option", { name: /product/iu }));
		await userEvent.click(within(dialog).getByRole("option", { name: /design system/iu }));
		await userEvent.click(within(dialog).getByRole("button", { name: /add 2 collections/iu }));

		await expectSettledVisible(await within(dialog).findByText(/adding 1 of 2…/iu));
		await expectSettledVisible(await within(dialog).findByText(/adding 2 of 2…/iu));
	},
};
