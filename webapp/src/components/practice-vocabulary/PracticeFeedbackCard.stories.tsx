import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent, within } from "storybook/test";

import { text, work } from "@/components/common/feedback-text";
import { NEW_FEEDBACK_CARD } from "@/stories/practice-feedback-cards-story-mock-data";
import { conversation, issue, pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectTouchTarget } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

import { PracticeFeedbackCard } from "./PracticeFeedbackCard";

const card = NEW_FEEDBACK_CARD;
const twoClean = [21, 22].map(pullRequest);
const threeClean = [20, 21, 22].map(pullRequest);

const meta = {
	title: "Shared/Practice vocabulary/Feedback card",
	component: PracticeFeedbackCard,
	tags: ["autodocs"],
	args: {
		card,
		onRate: fn(),
		onSendComment: fn(),
		onSkipComment: fn(),
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
		// The state is the registry's badge; a new card's wears the mentor accent, the one place
		// the palette lets a status badge carry it.
		const badge = canvas.getByText("New").closest('[data-slot="badge"]');
		await expect(badge).toHaveClass("text-mentor");
		await expect(canvas.queryByText("Open")).toBeNull();
		// The number rule and the two day rules of `feedback-text` and `lib/dates`: a word below ten,
		// the short day in the strip, the full day and the minute in the footer. The label counts
		// what the strip shows, by the kind every piece in it carries.
		await expect(canvas.getByText("Seen on three pull requests")).toBeVisible();
		await expect(canvas.getByText("0 of 3 clean")).toBeVisible();
		await expect(canvas.getByText("28 Aug")).toBeVisible();
		await expect(canvas.getByText("Created 9 September, 2:10 pm")).toBeVisible();
		// The glyph leading the next-step band is decoration: nothing in the card is a checkbox the
		// reader could tick.
		await expect(canvas.queryByRole("checkbox")).toBeNull();
		await expect(canvas.getByRole("button", { name: "Helpful" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);

		// The strip's outcome icons are 14 px and a tooltip each; their pointer targets are still
		// the minimum.
		for (const icon of canvas.getAllByRole("button", { name: "Needs improvement:" })) {
			await expectTouchTarget(icon);
		}
		// The head names the practice as the one grey pill every practice surface uses.
		const pill = canvas.getByRole("button", { name: "Scope the change to one concern" });
		await expect(pill).toHaveAttribute("data-slot", "badge");
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
		const strip = canvas.getByText("Seen on three pull requests").parentElement;
		if (!strip) throw new Error("Expected the strip around its label.");
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
		if (!pageSize || !retries) throw new Error("One bullet per change the body names");
		await expect(rest).toHaveLength(1);
		// The lead-in is a bold run inside the item, and the raw Markdown is nowhere on screen.
		await expect(pageSize.querySelector("strong")).toHaveTextContent(/^Page size \(#17/);
		await expect(canvas.queryByText(/\*\*/)).toBeNull();
		// A backticked value is code, and the reference beside it is still the work's own link.
		await expect(within(pageSize).getByText("20", { selector: "code" })).toBeVisible();
		await expect(within(pageSize).getByRole("link", { name: /^#17/ })).toHaveAttribute(
			"target",
			"_blank",
		);
		await expect(retries.querySelector("em")).toHaveTextContent("three");
	},
};

/**
 * Two of the three clean pieces of work are in: the strip lists them after the evidence, each a
 * strength shown, and the meter is two-thirds full.
 */
export const TwoOfThreeClean: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("New")).toBeNull();
		await expect(canvas.getByText("Open").closest('[data-slot="badge"]')).not.toHaveClass(
			"text-mentor",
		);
		await expect(canvas.getByText("Seen on five pull requests")).toBeVisible();
		await expect(canvas.getByText("2 of 3 clean")).toBeVisible();
		const outcomeNames = canvas
			.getAllByText(/:$/, { selector: ".sr-only" })
			.map((name) => name.textContent.trim());
		await expect(outcomeNames).toStrictEqual([
			"Needs improvement:",
			"Needs improvement:",
			"Needs improvement:",
			"Strength shown:",
			"Strength shown:",
		]);
		// The wire names the clean work without a date, so the strip shows it by number alone.
		await expect(canvas.getByRole("link", { name: /^#22/ })).toBeVisible();
	},
};

/**
 * The work the strip under `label` links, in order; each link's own "(opens in a new tab)" is
 * dropped.
 */
function stripLabels(label: HTMLElement): string[] {
	const strip = label.parentElement;
	if (!strip) throw new Error("The strip's label sits in the strip");
	return within(strip)
		.getAllByRole("link")
		.map((link) => link.textContent.replace(/\s*\(opens.*$/, ""));
}

/**
 * The strip is a glance, not a log: of seven pieces of evidence it shows the newest five, and with
 * two clean pieces the newest three, in order and counted as shown; the wire's occurrence count
 * is untouched.
 */
export const StripCapped: Story = {
	args: {
		card: {
			...card,
			reviewedWork: [11, 12, 13, 14, 15, 16, 17].map((number) => ({
				ref: pullRequest(number),
				date: `2026-08-${String(number).padStart(2, "0")}`,
				outcome: "COMMISSION_PROBLEM" as const,
			})),
		},
	},
	play: async ({ canvas }) => {
		await expect(stripLabels(canvas.getByText("Seen on five pull requests"))).toStrictEqual([
			"#13",
			"#14",
			"#15",
			"#16",
			"#17",
		]);
	},
};

/** Clean work always shows in full; the evidence gives way to it. */
export const StripCappedWithCleanWork: Story = {
	args: {
		card: {
			...card,
			state: "open",
			reviewedWork: [11, 12, 13, 14, 15, 16, 17].map((number) => ({
				ref: pullRequest(number),
				date: `2026-08-${String(number).padStart(2, "0")}`,
				outcome: "COMMISSION_PROBLEM" as const,
			})),
			cleanWork: twoClean,
		},
	},
	play: async ({ canvas }) => {
		await expect(stripLabels(canvas.getByText("Seen on five pull requests"))).toStrictEqual([
			"#15",
			"#16",
			"#17",
			"#21",
			"#22",
		]);
	},
};

/**
 * The work resolved it: the card wears the success colour's wash and edge as a new card wears the
 * accent's, the badge, the green circle-check leading the band and the filled meter carry the
 * state, and the condition names the clean work.
 */
export const Resolved: Story = {
	args: {
		card: {
			...card,
			state: "resolved",
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
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("article", { name: args.card.headline })).toHaveClass(
			"border-success/35",
		);
		await expect(canvas.getByText("Resolved")).toBeVisible();
		await expect(canvas.queryByText("Open")).toBeNull();
		await expect(canvas.getByText("3 of 3 clean")).toBeVisible();
		await expect(canvas.getByText("Resolved 9 September")).toBeVisible();
		// Each reference carries its own "(opens in a new tab)", so the words are matched around
		// them.
		await expect(canvas.getByText(/came back clean$/)).toHaveTextContent(
			/^Resolved by the work on 9 September · #20.*, #21.* and #22.* came back clean$/,
		);
		await expect(canvas.getByRole("button", { name: "Helpful" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
	},
};

/** The rating buttons are the registry's two entries, words and icons alike. */
export const Helpful: Story = {
	args: { card: { ...card, state: "open" }, usefulness: "HELPFUL" },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("button", { name: "Helpful" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.getByRole("button", { name: "Not helpful" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Not helpful" }));
		await expect(args.onRate).toHaveBeenCalledWith("UNHELPFUL");
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
		// One reason at a time: choosing a second releases the first.
		await userEvent.click(canvas.getByRole("button", { name: "Not useful" }));
		await userEvent.click(canvas.getByRole("button", { name: "Already doing this" }));
		await expect(canvas.getByRole("button", { name: "Not useful" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await expect(canvas.getByRole("button", { name: "Already doing this" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		// An empty sentence does not send.
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSendComment).not.toHaveBeenCalled();
		await userEvent.type(field, "Each of these was already one concern.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSendComment).toHaveBeenCalledWith({
			reason: "already-doing",
			comment: "Each of these was already one concern.",
		});
	},
};

export const NotHelpful: Story = {
	args: { card: { ...card, state: "open" }, usefulness: "UNHELPFUL" },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("button", { name: "Not helpful" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Helpful" }));
		await expect(args.onRate).toHaveBeenCalledWith("HELPFUL");
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
				{ ref: pullRequest(16), date: "2026-08-24", outcome: "OMISSION_GAP" },
				{ ref: pullRequest(19), date: "2026-09-06", outcome: "COMMISSION_PROBLEM" },
				{ ref: pullRequest(20), date: "2026-09-03", outcome: "SAFE_AVOIDANCE" },
			],
			cleanWork: [pullRequest(21)],
			nextStep:
				"Before the file list, write one paragraph on the problem and the decision you took.",
		},
	},
	play: async ({ canvas }) => {
		// Each outcome names itself once in the strip, oldest first whatever order the work was
		// given in — #16 on 24 Aug, #20 on 3 Sep, #19 on 6 Sep — and the clean piece last.
		const outcomeNames = canvas
			.getAllByText(/:$/, { selector: ".sr-only" })
			.map((name) => name.textContent.trim());
		await expect(outcomeNames).toStrictEqual([
			"Expected practice missing:",
			"Risk avoided:",
			"Needs improvement:",
			"Strength shown:",
		]);
	},
};

/**
 * Work at GitLab is named by the provider's noun: the strip counts merge requests, not pull
 * requests, and the wire's own labels carry the sigil.
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
		await expect(canvas.getByText("Seen on three merge requests")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /^!17/ })).toBeVisible();
	},
};

/**
 * Evidence of more than one kind — a pull request and an issue, and clean work of a third — is
 * counted as pieces of work: no kind is claimed for the strip that not every piece bears out.
 */
export const MixedWork: Story = {
	args: {
		card: {
			...card,
			state: "open",
			reviewedWork: [
				{ ref: pullRequest(17), date: "2026-08-28", outcome: "COMMISSION_PROBLEM" },
				{ ref: issue(13), date: "2026-09-03", outcome: "OMISSION_GAP" },
			],
			cleanWork: [conversation("#releases")],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Seen on three pieces of work")).toBeVisible();
		await expect(canvas.queryByText(/pull requests/)).toBeNull();
	},
};

/** The pill and the group name fall back to neutral grey when the group has no colour. */
export const WithoutGroupColor: Story = {
	args: { card: { ...card, state: "open", groupColor: undefined } },
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
					date: "2026-08-12",
					outcome: "OMISSION_GAP",
				},
			],
			cleanWork: [conversation("#releases")],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
		// The body names it in its own words, since only a number carries an address to link to.
		await expect(canvas.getByText(/^In #backend-review the outage/)).toBeVisible();
		for (const word of [canvas.getByText("#backend-review"), canvas.getByText("#releases")]) {
			await expect(word.tagName).toBe("SPAN");
			await expect(word).not.toHaveClass("hover:underline");
		}
	},
};

/** The reader marked it addressed before the work did: the meter stays where the work left it. */
export const Addressed: Story = {
	args: {
		card: {
			...card,
			state: "resolved",
			cleanWork: twoClean,
			condition: [text("Marked as addressed on 9 September")],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Resolved")).toBeVisible();
		await expect(canvas.getByText("2 of 3 clean")).toBeVisible();
		await expect(canvas.getByText("Marked as addressed on 9 September")).toBeVisible();
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
			timestamp: "2026-09-09T09:00:00Z",
		},
	},
	play: async ({ args, canvas }) => {
		const article = canvas.getByRole("article", { name: args.card.headline });
		await expect(article).not.toHaveClass("border-success/35");
		await expect(article).not.toHaveClass("border-mentor/35");
		await expect(canvas.getByText("Closed")).toBeVisible();
		await expect(canvas.queryByText("Resolved")).toBeNull();
		await expect(canvas.getByText("2 of 3 clean")).toBeVisible();
		await expect(canvas.getByText("Closed 9 September")).toBeVisible();
		await expect(
			canvas.getByText("Closed on 9 September · the practice's review rules changed"),
		).toBeVisible();
	},
};

/**
 * A rating on its way to the server: the chosen button says so, and the rest wait rather than
 * take a second press.
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
		onLearnMore: undefined,
		onOpenPractice: undefined,
		onOpenGroup: undefined,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Helpful" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Not helpful" })).toBeNull();
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
