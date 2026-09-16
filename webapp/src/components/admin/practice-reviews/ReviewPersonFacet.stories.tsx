import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";

import { MEMBER_PAGE_SIZE, type ReviewPeople, ReviewPersonFacet } from "./ReviewPersonFacet";
import { manyMembers, workspaceMembers } from "./story-mock-data";

function peopleFrom(
	members: typeof workspaceMembers,
	rest: Partial<ReviewPeople> = {},
): ReviewPeople {
	return {
		options: members
			.filter((member): member is typeof member & { userId: number } => member.userId != null)
			.map((member) => ({
				userId: member.userId,
				label: member.userName ?? `#${member.userId}`,
				secondary: member.userLogin,
			})),
		capped: members.length >= MEMBER_PAGE_SIZE,
		isLoading: false,
		isError: false,
		...rest,
	};
}

/**
 * Single-select rather than multi, because the search schema carries one id and the API takes one: a
 * multi-select trigger that silently kept only the last choice would lie about what it did.
 *
 * The people come in as a prop. `useReviewPeople` fetches them for the two review lists, and one
 * request serves both facets on a screen; here they are a fixture, which is why every state below —
 * including the failure — is a story rather than a mocked request.
 */
const meta = {
	component: ReviewPersonFacet,
	parameters: { layout: "centered", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: {
		title: "Developer",
		people: peopleFrom(workspaceMembers),
		selected: undefined,
		onChange: fn(),
	},
	// Controlled: `selected` comes back through the same prop the choice is reported on, so a frozen
	// value would leave the list looking dead after a click.
	render: (args) => (
		<Stateful<number | undefined> initial={args.selected}>
			{(selected, setSelected) => (
				<ReviewPersonFacet
					{...args}
					selected={selected}
					onChange={(userId) => {
						setSelected(userId);
						args.onChange(userId);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof ReviewPersonFacet>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Developer" }));
		const listbox = await screen.findByRole("listbox", { name: "Developer options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Grace Hopper/u }));
		await expect(args.onChange).toHaveBeenCalledWith(9);
		await canvas.findByRole("combobox", { name: "Developer: Grace Hopper" });
	},
};

/** "Recipient" on Delivery, "Developer" on Observations — the two are not always the same person. */
export const NamedForItsList: Story = {
	args: { title: "Recipient" },
};

export const ClearTheSelection: Story = {
	args: { selected: 9 },
	play: async ({ args, canvas, userEvent }) => {
		await canvas.findByRole("combobox", { name: "Developer: Grace Hopper" });
		await userEvent.click(canvas.getByRole("combobox", { name: "Developer: Grace Hopper" }));
		await userEvent.click(await screen.findByRole("button", { name: "Clear selection" }));
		await expect(args.onChange).toHaveBeenCalledWith(undefined);
	},
};

/**
 * The person filtering the list is not on the member page — they have left the workspace, or the page
 * has not arrived yet. The trigger names them from `fallbackName` rather than showing a bare id, and
 * that name must be *theirs*: read off the first row it is right only while the filter is on.
 */
export const SomebodyNoLongerInTheWorkspace: Story = {
	args: { selected: 404, fallbackName: "Barbara Liskov" },
	play: async ({ canvas }) => {
		await canvas.findByRole("combobox", { name: "Developer: Barbara Liskov" });
	},
};

/** Without even a name to fall back on, the id is shown rather than an empty badge. */
export const SomebodyWithNoNameAtAll: Story = {
	args: { selected: 404 },
	play: async ({ canvas }) => {
		await canvas.findByRole("combobox", { name: "Developer: #404" });
	},
};

/**
 * The members endpoint takes `page` and `size` and no name filter, so this control matches within one
 * fetched page. Past that page the search box would otherwise answer "No matches", which reads as
 * "that person is not in this workspace"; lifting the cap needs a server-side name query.
 */
export const MorePeopleThanTheFacetCanList: Story = {
	args: { people: peopleFrom(manyMembers(MEMBER_PAGE_SIZE)) },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Developer" }));
		await screen.findByRole("listbox", { name: "Developer options" });
		await screen.findByText(/Showing the first 100 members/u);
	},
};

export const WhileThePeopleLoad: Story = {
	args: { people: { options: [], capped: false, isLoading: true, isError: false } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Developer" })).toBeDisabled();
	},
};

/**
 * The list failing is not the same as a workspace with nobody in it, and a facet that said "No people
 * in this workspace" after a 500 would send the reader looking for a membership problem.
 */
export const ThePeopleCouldNotBeLoaded: Story = {
	args: { people: { options: [], capped: false, isLoading: false, isError: true } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Developer" }));
		await screen.findByText("Could not load people");
	},
};

export const NobodyInThisWorkspace: Story = {
	args: { people: { options: [], capped: false, isLoading: false, isError: false } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Developer" }));
		await screen.findByText("No people in this workspace");
	},
};
