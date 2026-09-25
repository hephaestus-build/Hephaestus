import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent, within } from "storybook/test";

import { NEW_FEEDBACK_CARD } from "@/stories/practice-feedback-cards-story-mock-data";
import { conversation, issue, pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { expectTouchTarget } from "@/test/controls";

import { text, work } from "./feedback-text";
import { PracticeFeedbackCard } from "./PracticeFeedbackCard";

const card = NEW_FEEDBACK_CARD;
/** One clean piece of work, reviewed on the day given, in the reader's own time zone. */
const clean = (number: number, day: string) => ({
	ref: pullRequest(number),
	date: new Date(`${day}T00:00`),
});
const twoClean = [clean(21, "2026-09-07"), clean(22, "2026-09-09")];
const threeClean = [clean(20, "2026-09-07"), clean(21, "2026-09-08"), clean(22, "2026-09-09")];

/** The strip's outcome icons, matched by the names they carry for a screen reader. */
const OUTCOME_NAME =
	/^(?:Strength shown|Risk avoided|Needs improvement|Expected practice missing)$/u;
const names = (icons: HTMLElement[]) => icons.map((icon) => icon.getAttribute("aria-label"));

const meta = {
	component: PracticeFeedbackCard,
	tags: ["autodocs"],
	args: {
		card,
		onRate: fn(),
		onSendComment: fn(),
		onSkipComment: fn(),
		onResolve: fn(),
		onLearnMore: fn(),
		onOpenPractice: fn(),
		onOpenGroup: fn(),
	},
	// One piece of feedback is one object; a JSON control over it would edit nothing a reader
	// could not read off the stories below.
	argTypes: {
		card: { control: false },
	},
	decorators: [
		(Story) => (
			<div className="mx-auto max-w-4xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof PracticeFeedbackCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing clean yet: the "New" badge in the accent, the blue wash and an empty meter. */
export const New: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("article", { name: args.card.headline })).toBeVisible();
		await expect(canvas.getByText("New")).toBeVisible();
		await expect(canvas.queryByText("Open")).toBeNull();
		// The number rule and the two day rules of `feedback-text` and `lib/dates`: a word below ten,
		// the short day in the strip, the full day and the minute in the footer. The label counts
		// the wire's distinct pieces of evidence, by the kind every piece carries.
		await expect(canvas.getByText("Newest three pull requests")).toBeVisible();
		await expect(canvas.getByRole("meter", { name: "Clean work in a row" })).toHaveAttribute(
			"aria-valuetext",
			"0 of 3 clean",
		);
		await expect(canvas.getByText("28 Aug")).toBeVisible();
		await expect(canvas.getByText("Created 9 September, 2:10 pm")).toBeVisible();
		// The glyph leading the next-step band is decoration: nothing in the card is a checkbox the
		// reader could tick.
		await expect(canvas.queryByRole("checkbox")).toBeNull();
		// Nothing rated yet, and each rating button writes the registry's own value.
		await expect(canvas.getByRole("button", { name: "Helpful" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Not helpful" }));
		await expect(args.onRate).toHaveBeenCalledWith("UNHELPFUL");
		// The other way to close the card, beside the clean work: nothing answered yet, and each
		// answer writes the registry's own value.
		const response = within(canvas.getByRole("group", { name: "Your response" }));
		await expect(response.getByRole("button", { name: "Not applicable" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await userEvent.click(response.getByRole("button", { name: "Addressed" }));
		await expect(args.onResolve).toHaveBeenCalledWith("ADDRESSED");

		// The head names the practice as a control that opens it, large enough to press.
		const pill = canvas.getByRole("button", { name: "Scope the change to one concern" });
		await expectTouchTarget(pill);
		await userEvent.click(pill);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("scope-one-reviewable-change");
		// The group's icon stands beside the group's name, not inside the practice pill.
		const groupName = canvas.getByRole("button", { name: "Packaging work for review" });
		await expect(groupName.parentElement?.querySelector("svg")).not.toBeNull();
		await expect(
			canvas.getByRole("button", { name: "Scope the change to one concern" }).querySelector("svg"),
		).toBeNull();
		await userEvent.click(groupName);
		await expect(args.onOpenGroup).toHaveBeenCalledWith("review-ready-work");
		// The strip reads left to right in time: #17 on 28 Aug, #20 on 3 Sep, #19 on 6 Sep.
		const strip = canvas.getByText("Newest three pull requests").parentElement;
		if (!strip) {
			throw new Error("Expected the strip around its label.");
		}
		await expect(
			within(strip)
				.getAllByRole("link")
				.map((link) => link.textContent),
		).toStrictEqual([
			"#17 (opens in a new tab)",
			"#20 (opens in a new tab)",
			"#19 (opens in a new tab)",
		]);
		await userEvent.click(canvas.getByRole("button", { name: "Learn more about this practice" }));
		await expect(args.onLearnMore).toHaveBeenCalledOnce();
	},
};

/**
 * The body is the composer's own Markdown: a lead-in, then one bullet per piece of work with its
 * name in bold and the value it turns on in code. Every reference to a piece of work the card
 * carries is still that piece's link, wherever the Markdown puts it.
 */
export const MarkdownBody: Story = {
	args: {
		card: {
			...card,
			state: "open",
			practiceSlug: "state-the-value-under-test",
			practiceName: "State the value the change exists to set",
			headline: "The thing a change exists to set is the thing no test states",
			body: [
				"Three changes here set a value that no test says out loud.",
				"",
				"- **Page size (#17)** — the paging test asserts the response is not empty, never that it holds `20` items.",
				"- **Retry ceiling (#19)** — the retry test waits for success and never states the ceiling of *three* attempts.",
				"- **Timeout (#20)** — the timeout moved to 90 seconds with nothing naming the new bound.",
			].join("\n"),
			nextStep:
				"When a change picks a number, write the test that fails if the number changes back.",
		},
	},
	play: async ({ canvas }) => {
		const [pageSize, retries, ...rest] = within(canvas.getByRole("list")).getAllByRole("listitem");
		if (!pageSize || !retries) {
			throw new Error("One bullet per change the body names");
		}
		await expect(rest).toHaveLength(1);
		// The lead-in is a bold run inside the item, and the raw Markdown is nowhere on screen.
		await expect(pageSize.querySelector("strong")).toHaveTextContent(/^Page size \(#17/u);
		await expect(canvas.queryByText(/\*\*/u)).toBeNull();
		// A backticked value is code, and the reference beside it is still the work's own link.
		await expect(within(pageSize).getByText("20", { selector: "code" })).toBeVisible();
		await expect(within(pageSize).getByRole("link", { name: /^#17/u })).toHaveAttribute(
			"target",
			"_blank",
		);
		await expect(retries.querySelector("em")).toHaveTextContent("three");
	},
};

/**
 * Two of the three clean pieces of work are in: the strip lists them where their review dates put
 * them, each a strength shown, and the meter is two-thirds full. The label still counts the three
 * pieces the habit was seen on; the clean work is what resolves the feedback.
 */
export const TwoOfThreeClean: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("New")).toBeNull();
		await expect(canvas.getByText("Open")).toBeVisible();
		await expect(canvas.getByText("Newest five pull requests")).toBeVisible();
		// A count toward a threshold: the meter says it in words as well as in its value.
		const meter = canvas.getByRole("meter", { name: "Clean work in a row" });
		await expect(meter).toHaveAttribute("aria-valuenow", "2");
		await expect(meter).toHaveAttribute("aria-valuemax", "3");
		await expect(meter).toHaveAttribute("aria-valuetext", "2 of 3 clean");
		await expect(names(canvas.getAllByRole("button", { name: OUTCOME_NAME }))).toStrictEqual([
			"Needs improvement",
			"Needs improvement",
			"Needs improvement",
			"Strength shown",
			"Strength shown",
		]);
		// A clean piece is dated like the evidence beside it.
		await expect(canvas.getByRole("link", { name: /^#22/u })).toBeVisible();
		await expect(canvas.getByText("9 Sep")).toBeVisible();
	},
};

/**
 * The work resolved it: the card wears the success colour's wash, the badge, the green tick leading
 * the band and the filled meter carry the state, and the footer dates the resolution.
 */
export const Resolved: Story = {
	args: {
		card: {
			...card,
			state: "resolved",
			resolvedBy: "WORK",
			cleanWork: threeClean,
			condition: [
				text("Resolved by the work on 9 September · "),
				work(pullRequest(20)),
				text(", "),
				work(pullRequest(21)),
				text(" and "),
				work(pullRequest(22)),
				text(" came back clean"),
			],
		},
		usefulness: "HELPFUL",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Resolved")).toBeVisible();
		await expect(canvas.queryByText("Open")).toBeNull();
		await expect(canvas.getByText("3 of 3 clean")).toBeVisible();
		await expect(canvas.getByText("Resolved 9 September")).toBeVisible();
		// Each reference carries its own "(opens in a new tab)", so the words are matched around
		// them.
		await expect(canvas.getByText(/came back clean$/u)).toHaveTextContent(
			/^Resolved by the work on 9 September · #20.*, #21.* and #22.* came back clean$/u,
		);
		await expect(canvas.getByRole("button", { name: "Helpful" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		// The work's resolution is not the reader's to take back, so there is no answer to give.
		await expect(canvas.queryByRole("group", { name: "Your response" })).toBeNull();
	},
};

/**
 * The reader marked it addressed: the card resolves on the day they did, the meter stays where the
 * work left it, and the answer stays pressed under it, so a second press takes it back and reopens
 * the card.
 */
export const MarkedAsAddressed: Story = {
	args: {
		card: {
			...card,
			state: "resolved",
			resolvedBy: "DEVELOPER",
			cleanWork: twoClean,
			condition: [text("Marked as addressed on 9 September")],
			timestamp: new Date("2026-09-09T16:05:00"),
		},
		resolution: "ADDRESSED",
	},
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("Resolved 9 September")).toBeVisible();
		await expect(canvas.getByText("Marked as addressed on 9 September")).toBeVisible();
		await expect(canvas.getByText("2 of 3 clean")).toBeVisible();
		const response = within(canvas.getByRole("group", { name: "Your response" }));
		const addressed = response.getByRole("button", { name: "Addressed" });
		await expect(addressed).toHaveAttribute("aria-pressed", "true");
		await expect(response.getByRole("button", { name: "Not applicable" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await userEvent.click(addressed);
		await expect(args.onResolve).toHaveBeenCalledWith("ADDRESSED");
	},
};

/** "Helpful" opens the note band: an optional line, sent or skipped. */
export const HelpfulNoteOpen: Story = {
	args: { card: { ...card, state: "open" }, usefulness: "HELPFUL", commentOpen: true },
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: "What worked about this feedback?" });
		await expect(field).toBeVisible();
		await expect(field).not.toBeRequired();
		await expect(canvas.queryByRole("group", { name: "Reason" })).toBeNull();
		await userEvent.type(field, "The split into two merge requests was the right call.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSendComment).toHaveBeenCalledWith({
			comment: "The split into two merge requests was the right call.",
		});
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(args.onSkipComment).toHaveBeenCalledOnce();
	},
};

/**
 * "Not helpful" asks for a reason and a sentence; the sentence is what the dispute has to carry.
 */
export const NotHelpfulReasonOpen: Story = {
	args: { card: { ...card, state: "open" }, usefulness: "UNHELPFUL", commentOpen: true },
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: "What was missed?" });
		await expect(field).toBeRequired();
		const reasons = within(canvas.getByRole("group", { name: "Reason" })).getAllByRole("button");
		await expect(reasons.map((reason) => reason.textContent)).toStrictEqual([
			"Not accurate",
			"Not useful",
			"Already doing this",
		]);
		await userEvent.click(canvas.getByRole("button", { name: "Already doing this" }));
		await userEvent.type(field, "Each of these was already one concern.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSendComment).toHaveBeenCalledWith({
			reason: "already-doing",
			comment: "Each of these was already one concern.",
		});
	},
};

/**
 * Every outcome the strip can show, on one card: a strength, a risk avoided, a problem and a gap.
 * The icon is the only thing telling the two good and the two bad apart, so each also names
 * itself for a screen reader.
 */
export const EveryOutcome: Story = {
	args: {
		card: {
			...card,
			state: "open",
			practiceSlug: "describe-what-and-why",
			practiceName: "Describe what changed and why",
			headline: "Descriptions named the what, rarely the why",
			body: "#16 and #19 listed the files touched but not the problem behind them; the reviewer on #19 asked in the first comment what the change was for.",
			reviewedWork: [
				{ ref: pullRequest(16), date: new Date("2026-08-24T00:00"), outcome: "OMISSION_GAP" },
				{ ref: pullRequest(19), date: new Date("2026-09-06T00:00"), outcome: "COMMISSION_PROBLEM" },
				{ ref: pullRequest(20), date: new Date("2026-09-03T00:00"), outcome: "SAFE_AVOIDANCE" },
				{
					ref: pullRequest(21),
					date: new Date("2026-09-08T00:00"),
					outcome: "DEMONSTRATED_STRENGTH",
				},
			],
			nextStep:
				"Before the file list, write one paragraph on the problem and the decision you took.",
		},
	},
	play: async ({ canvas }) => {
		// Each outcome names itself once in the strip, oldest first whatever order the work was
		// given in — #16 on 24 Aug, #20 on 3 Sep, #19 on 6 Sep, #21 on 8 Sep.
		await expect(names(canvas.getAllByRole("button", { name: OUTCOME_NAME }))).toStrictEqual([
			"Expected practice missing",
			"Risk avoided",
			"Needs improvement",
			"Strength shown",
		]);
	},
};

/**
 * Work at GitLab is named by the provider's noun: the strip counts merge requests, not pull
 * requests, the wire's own labels carry the sigil, and the glyph leading the label is the forge the
 * work lives at rather than its kind.
 */
export const GitLabWork: Story = {
	args: {
		card: {
			...card,
			state: "open",
			reviewedWork: card.reviewedWork.map((piece) => ({
				...piece,
				ref: { ...piece.ref, provider: "GITLAB", label: `!${piece.ref.id}` },
			})),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Newest three merge requests")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /^!17/u })).toBeVisible();
		// The glyph is decorative, so the mark is read off the brand icon's own <title>.
		canvas.getByTitle("GitlabIcon");
		await expect(canvas.queryByTitle("GithubIcon")).toBeNull();
	},
};

/**
 * Evidence of more than one kind — a pull request and an issue, with clean work of a third — is
 * counted as pieces of work: no kind is claimed for the evidence that not every piece bears out,
 * and the clean conversation is not among what was seen.
 */
export const MixedWork: Story = {
	args: {
		card: {
			...card,
			state: "open",
			reviewedWork: [
				{ ref: pullRequest(17), date: new Date("2026-08-28T00:00"), outcome: "COMMISSION_PROBLEM" },
				{ ref: issue(13), date: new Date("2026-09-03T00:00"), outcome: "OMISSION_GAP" },
			],
			cleanWork: [{ ref: conversation("#releases"), date: new Date("2026-09-05T00:00") }],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Newest three pieces of work")).toBeVisible();
		await expect(canvas.queryByText(/pull requests/u)).toBeNull();
	},
};

/**
 * A practice in no group: the head says the feedback is unassigned and leaves it at that, since
 * there is no group level to open. The pill and the name wear the neutral grey any group without a
 * colour falls back to.
 */
export const WithoutGroup: Story = {
	args: { card: { ...card, state: "open", group: undefined } },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("Unassigned")).toBeVisible();
		// A word, not a control: nothing to press and nowhere to go.
		await expect(canvas.queryByRole("button", { name: "Unassigned" })).toBeNull();
		await expect(canvas.queryByRole("link", { name: "Unassigned" })).toBeNull();
		await expect(args.onOpenGroup).not.toHaveBeenCalled();
	},
};

/**
 * Work the provider exposes no page for — a Slack channel — is named in the body, the strip and
 * the clean work alike as a word: no link, and none of the link's hover either.
 */
export const WorkWithoutAnAddress: Story = {
	args: {
		card: {
			...card,
			state: "open",
			body: "In #backend-review the outage that held the release was first mentioned the next morning.",
			reviewedWork: [
				{
					ref: conversation("#backend-review"),
					date: new Date("2026-08-12T00:00"),
					outcome: "OMISSION_GAP",
				},
			],
			cleanWork: [{ ref: conversation("#releases"), date: new Date("2026-08-20T00:00") }],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
		await expect(canvas.getByText("Newest two conversations")).toBeVisible();
		// The body names it in its own words, since only a number carries an address to link to.
		await expect(canvas.getByText(/^In #backend-review the outage/u)).toBeVisible();
	},
};

/**
 * The practice's review rules changed after the card was written: it closes unresolved, keeps
 * its evidence and the meter where the work left it, wears no wash, and the condition says why.
 */
export const Closed: Story = {
	args: {
		card: {
			...card,
			state: "closed",
			cleanWork: twoClean,
			condition: [text("Closed on 9 September · the practice's review rules changed")],
			timestamp: new Date("2026-09-09T09:00"),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Closed")).toBeVisible();
		await expect(canvas.queryByText("Resolved")).toBeNull();
		await expect(canvas.getByText("2 of 3 clean")).toBeVisible();
		await expect(canvas.getByText("Closed 9 September")).toBeVisible();
		await expect(
			canvas.getByText("Closed on 9 September · the practice's review rules changed"),
		).toBeVisible();
		// Closed unresolved, and no answer reopens it.
		await expect(canvas.queryByRole("group", { name: "Your response" })).toBeNull();
	},
};

/**
 * The moment after a press on "Helpful": the rating being written is the one shown, saying so,
 * and the other rating and the band the press opened wait rather than take a second press.
 */
export const RatingPending: Story = {
	args: {
		card: { ...card, state: "open" },
		usefulness: "HELPFUL",
		commentOpen: true,
		isPending: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
		await expect(canvas.queryByRole("button", { name: "Helpful" })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Not helpful" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Sending…" })).toBeDisabled();
	},
};

/**
 * Mounted where nothing can be rated or opened — a surface that only shows the card — it draws
 * no control it cannot answer: hidden, not disabled, since no press could ever unlock them.
 */
export const WithoutHandlers: Story = {
	args: {
		card: { ...card, state: "open" },
		onRate: undefined,
		onSendComment: undefined,
		onSkipComment: undefined,
		onResolve: undefined,
		onLearnMore: undefined,
		onOpenPractice: undefined,
		onOpenGroup: undefined,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Helpful" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Not helpful" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Addressed" })).toBeNull();
		await expect(
			canvas.queryByRole("button", { name: "Learn more about this practice" }),
		).toBeNull();
		await expect(
			canvas.queryByRole("button", { name: "Scope the change to one concern" }),
		).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Packaging work for review" })).toBeNull();
	},
};

export const MobileReflow: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
