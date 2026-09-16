import type { Meta, StoryObj } from "@storybook/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { delay, HttpResponse, http } from "msw";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { AddCollectionDialog } from "./AddCollectionDialog";

const meta = {
	component: AddCollectionDialog,
	parameters: {
		layout: "centered",
		// One MSW worker answers a whole Docs page, so each story gets its own frame until MSW goes.
		docs: { story: { inline: false, height: "600px" } },
	},
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<QueryClientProvider
				client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
			>
				<Story />
			</QueryClientProvider>
		),
	],
	args: {
		workspaceSlug: "demo-workspace",
		open: true,
		onOpenChange: fn(),
		onRegister: fn(),
	},
} satisfies Meta<typeof AddCollectionDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

const candidates = [
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

type JsonBody = Record<string, unknown> | Record<string, unknown>[];

const candidatesHandler = (body: JsonBody, init?: { status?: number; delayMs?: number }) =>
	http.get("*/workspaces/:workspaceSlug/outline/collections/candidates", async () => {
		if (init?.delayMs !== undefined) {
			await delay(init.delayMs);
		}
		return HttpResponse.json(body, { status: init?.status ?? 200 });
	});

export const Loading: Story = {
	parameters: { msw: { handlers: [candidatesHandler(candidates, { delayMs: 100_000 })] } },
};

export const ProbeFailed: Story = {
	parameters: {
		msw: {
			handlers: [
				candidatesHandler(
					{
						type: "about:blank",
						title: "Bad Gateway",
						status: 502,
						detail: "Outline did not respond to collections.list.",
					},
					{ status: 502 },
				),
			],
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(await within(dialog).findByText(/outline did not respond/iu));
		within(dialog).getByRole("button", { name: /^retry$/iu });
	},
};

export const NoVisibleCollections: Story = {
	parameters: { msw: { handlers: [candidatesHandler([])] } },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(
			await within(dialog).findByText(/this token cannot see any collections/iu),
		);
		within(dialog).getByText(/add the bot user/iu);
	},
};

export const PopulatedSearchable: Story = {
	parameters: { msw: { handlers: [candidatesHandler(candidates)] } },
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
	parameters: { msw: { handlers: [candidatesHandler(candidates)] } },
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
	parameters: { msw: { handlers: [candidatesHandler(candidates)] } },
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		const search = await within(dialog).findByRole("combobox");
		await userEvent.click(search);
		await waitFor(() => expect(search).toHaveFocus());

		await userEvent.keyboard("{ArrowDown}");
		const engineering = within(dialog).getByRole("option", { name: /engineering/iu });
		await waitFor(() => expect(search).toHaveAttribute("aria-activedescendant", engineering.id));

		const product = within(dialog).getByRole("option", { name: /product/iu });
		await userEvent.keyboard("{ArrowDown}");
		await waitFor(() => expect(search).toHaveAttribute("aria-activedescendant", product.id));

		await userEvent.keyboard("{ArrowUp}");
		await waitFor(() => expect(search).toHaveAttribute("aria-activedescendant", engineering.id));

		await userEvent.keyboard("{Enter}");
		await expect(
			within(dialog).getByRole("button", { name: /^add collections$/iu }),
		).toBeDisabled();

		await userEvent.keyboard("{ArrowDown}{Enter}");
		await waitFor(() => expect(product).toHaveAttribute("aria-selected", "true"));
		await expect(args.onRegister).not.toHaveBeenCalled();

		await userEvent.keyboard("{Enter}");
		await waitFor(() => expect(product).toHaveAttribute("aria-selected", "false"));
	},
};

export const EmptySearchResult: Story = {
	parameters: { msw: { handlers: [candidatesHandler(candidates)] } },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await userEvent.type(await within(dialog).findByRole("combobox"), "nothing-matches-this");
		await waitFor(() => expect(within(dialog).getByText(/no collections match your search/iu)));
		await expect(within(dialog).queryByRole("option")).not.toBeInTheDocument();
	},
};

export const AllAlreadyMirrored: Story = {
	parameters: {
		msw: {
			handlers: [candidatesHandler(candidates.map((c) => ({ ...c, alreadyMirrored: true })))],
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(
			await within(dialog).findByText(/every visible collection is already mirrored/iu),
		);
		await expect(within(dialog).queryByRole("listbox")).not.toBeInTheDocument();
	},
};

export const RegisteringSequentially: Story = {
	parameters: { msw: { handlers: [candidatesHandler(candidates)] } },
	args: {
		onRegister: fn(async () => {
			await new Promise((resolve) => {
				setTimeout(resolve, 400);
			});
		}),
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
