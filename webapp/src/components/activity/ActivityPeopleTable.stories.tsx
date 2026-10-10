import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import {
	LARGE_PEOPLE,
	PEOPLE,
	peopleOf,
	readyPeople,
	REPOSITORIES,
} from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { ActivityPeopleTable } from "./ActivityPeopleTable";

const meta = {
	component: ActivityPeopleTable,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyPeople(peopleOf(PEOPLE)),
		providerType: "GITHUB",
		order: { sort: "contributions", desc: true },
		onOrderChange: fn(),
		repositories: REPOSITORIES,
		repo: [],
		onRepoChange: fn(),
	},
	render: (args) => (
		<Stateful initial={args.order}>
			{(order, setOrder) => (
				<ActivityPeopleTable
					{...args}
					order={order}
					onOrderChange={(next) => {
						args.onOrderChange(next);
						setOrder(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof ActivityPeopleTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Each row's position and name, in the order the table shows them. */
function rows(table: HTMLElement): string[][] {
	return within(table)
		.getAllByRole("row")
		.slice(1)
		.map((row) => [
			within(row).getAllByRole("cell")[0]?.textContent ?? "",
			within(row).getByRole("link").textContent,
		]);
}

/** Most contributions first; Bob and Dana tie, share position 2, and the next is 4. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "People" });
		await expect(rows(table)).toStrictEqual([
			["1", "Ada Lovelace"],
			["2", "Bob Brenner"],
			["2", "Dana Okafor"],
			["4", "Chen Wei"],
			["5", "Élodie Brière"],
		]);
		await expect(within(table).getByRole("columnheader", { name: "Position" })).toBeVisible();
		await expect(
			within(table).getByRole("columnheader", { name: /^Contributions/u }),
		).toHaveAttribute("aria-sort", "descending");
	},
};

/** A press on a count sorts by it, most first, and says so to a screen reader. */
export const SortByReviews: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^Reviews/u }));
		await expect(args.onOrderChange).toHaveBeenCalledWith({ sort: "reviews", desc: true });
		await expect(canvas.getByRole("columnheader", { name: /^Reviews/u })).toHaveAttribute(
			"aria-sort",
			"descending",
		);
	},
};

/** In name order no row has a position. */
export const ByName: Story = {
	args: { order: { sort: "name", desc: false } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("columnheader", { name: "Position" })).not.toBeInTheDocument();
	},
};

/** A search hides rows but keeps their positions, and folds case and accents. */
export const Search: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.type(canvas.getByRole("searchbox", { name: "Search people" }), "elodie");
		await expect(rows(canvas.getByRole("table", { name: "People" }))).toStrictEqual([
			["5", "Élodie Brière"],
		]);
		await expect(canvas.getByText("1 of 5 people")).toBeVisible();
	},
};

export const NoMatch: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.type(canvas.getByRole("searchbox", { name: "Search people" }), "zz");
		await expect(canvas.getByText("No one matches your search")).toBeVisible();
	},
};

/** 250 people render 50 at a time; the end's press stays for the keyboard. */
export const Large: Story = {
	args: { state: readyPeople(peopleOf(LARGE_PEOPLE)) },
	play: async ({ canvas, userEvent }) => {
		const table = canvas.getByRole("table", { name: "People" });
		await expect(rows(table)).toHaveLength(50);
		await userEvent.click(canvas.getByRole("button", { name: "Show more people" }));
		await expect(rows(table)).toHaveLength(100);
	},
};

export const FilteredByRepository: Story = {
	args: { repo: ["hephaestus-build/Hephaestus"] },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(args.onRepoChange).toHaveBeenCalledWith([]);
	},
};

export const Empty: Story = {
	args: { state: readyPeople(peopleOf([])) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No contributions in this range")).toBeVisible();
	},
};

/** With repositories picked, the empty table says the pick is why, and the pick stays clearable. */
export const EmptyForRepositories: Story = {
	args: { state: readyPeople(peopleOf([])), repo: ["hephaestus-build/Hephaestus"] },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No contributions to these repositories in this range"),
		).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Reset" })).toBeVisible();
	},
};

export const Stale: Story = {
	args: { state: { status: "ready", people: peopleOf(PEOPLE), stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "People" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "People" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

/**
 * A repository the workspace no longer has fails the read; the pick stays in the toolbar, named,
 * so it can be cleared.
 */
export const FailedForUnknownRepository: Story = {
	args: {
		state: { status: "error", error: new Error("Repository not found"), onRetry },
		repo: ["acme/old"],
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("combobox", { name: "Repository: acme/old" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(args.onRepoChange).toHaveBeenCalledWith([]);
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};
