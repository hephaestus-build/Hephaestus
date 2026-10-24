import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { NEW_FEEDBACK_CARD } from "@/stories/practice-feedback-cards-story-mock-data";
import { conversation, issue, pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { inStoryYear } from "@/stories/story-clock";
import { expectTouchTarget } from "@/test/controls";

import { text, work } from "./feedback-text";
import { PracticeFeedbackCard, PracticeFeedbackCardSkeleton } from "./PracticeFeedbackCard";

const card = NEW_FEEDBACK_CARD;
/** One clean piece of work, reviewed on the day given, in the reader's own time zone. */
const clean = (number: number, day: string) => ({
	ref: pullRequest(number),
	date: inStoryYear(`${day}T00:00`),
});
const twoClean = [clean(21, "09-10"), clean(22, "09-11")];
const threeClean = [clean(20, "09-10"), clean(21, "09-11"), clean(22, "09-12")];

/** The outcomes the timeline draws, in its order, read off the markers on its line. */
const outcomesOnTheLine = (list: HTMLElement) =>
	[...list.querySelectorAll<SVGElement>("svg[data-outcome]")].map(
		(marker) => marker.dataset.outcome,
	);

/** The work the timeline links, in its order, without the new-tab note. */
const linkedWork = (list: HTMLElement) =>
	within(list)
		.getAllByRole("link")
		.map((link) => link.textContent.replace(" (opens in a new tab)", ""));

const meta = {
	component: PracticeFeedbackCard,
	tags: ["autodocs"],
	args: {
		card,
		onRate: fn(),
		onSendComment: fn(),
		onSkipComment: fn(),
		onDisagree: fn(),
		onSendDisagreement: fn(),
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

/**
 * New feedback, nothing clean yet: the "New" badge in the accent and the blue wash. The top says
 * what it is about, with the whole explanation under "Why this was raised" and the practice's own
 * words as a small link. The tinted band says the next step;
 * "Work reviewed for this practice" under it holds the timeline on the left and the clean meter with the
 * reader's own answers on the right; the footer holds the ratings as quiet icon buttons. Thin
 * rules: above and below the band, and above the footer.
 */
export const New: Story = {
	play: async ({ args, canvas }) => {
		const article = canvas.getByRole("article", { name: args.card.headline });
		await expect(canvas.getByText("New")).toBeVisible();
		await expect(canvas.queryByText("Open")).toBeNull();
		// The number rule and the two day rules of `feedback-text` and `lib/dates`: a word below ten,
		// the short day on the timeline, the full day and the minute in the footer.
		await expect(canvas.getByText("Created 9 September, 2:10 pm")).toBeVisible();
		// The head names the kind and number of the work the pattern recurs in, beside the state; a
		// hover, focus or tap names each piece, linked.
		const seenIn = canvas.getByRole("button", {
			name: `Recurring in three pull requests: ${args.card.headline}`,
		});
		await expect(seenIn).toBeVisible();
		await expect(seenIn.nextElementSibling).toHaveTextContent("New");
		await userEvent.hover(seenIn);
		const works = await screen.findByRole("dialog", { name: "Recurring in three pull requests" });
		await expect(within(works).getAllByRole("link")).toHaveLength(3);
		// A short heading, then one work per line, newest first.
		// The panel opens with a short animation: wait for it to settle under load.
		await waitFor(async () => {
			await expect(within(works).getByText("Recurring in these pull requests")).toBeVisible();
		});
		const lines = within(works).getAllByRole("listitem");
		await expect(lines).toHaveLength(3);
		const days = lines.map((line) => Date.parse(line.querySelector("time")?.dateTime ?? ""));
		await expect([...days].sort((a, b) => b - a)).toStrictEqual(days);
		await userEvent.unhover(seenIn);
		// The glyph leading the next-step band is decoration: nothing in the card is a checkbox the
		// reader could tick.
		await expect(canvas.queryByRole("checkbox")).toBeNull();
		// The explanation sits under its own heading, which names what the paragraph is.
		const why = within(article).getByRole("heading", { name: "Why this was raised", level: 4 });
		await expect(why).toBeVisible();
		// The card's three section headings read as one set: one size, weight and colour, and one
		// step above what each names.
		const sectionHeadings = [
			"Why this was raised",
			"Next step",
			"Work reviewed for this practice",
		].map((name) => within(article).getByRole("heading", { name, level: 4 }));
		const looks = sectionHeadings.map((heading) => {
			const style = getComputedStyle(heading);
			return [style.fontSize, style.fontWeight, style.color].join(" ");
		});
		await expect(new Set(looks).size).toBe(1);
		await expect(looks[0]).toMatch(/^14px 600 /u);
		const steps = sectionHeadings.map(
			(heading) => getComputedStyle(heading.parentElement ?? heading).rowGap,
		);
		await expect(new Set(steps).size).toBe(1);
		await expect(
			why.compareDocumentPosition(
				canvas.getByText(/Reviewers had to follow two intentions in one diff\.$/u),
			),
		).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
		// The explanation shows whole, with no clamp and no "Show more".
		await expect(canvas.queryByRole("button", { name: /Show (?:more|less)/u })).toBeNull();
		await expect(
			canvas.getByText(/Reviewers had to follow two intentions in one diff\.$/u),
		).toBeVisible();
		// The timeline is the centre: every piece in time order, each read as its number, its day
		// and what the review saw.
		const timeline = canvas.getByRole("list", { name: "Where it happened" });
		const items = within(timeline).getAllByRole("listitem");
		await expect(items.map((item) => item.textContent)).toStrictEqual([
			"#17 (opens in a new tab)28 Aug, 28 August, needed attention, before this feedback",
			"#20 (opens in a new tab)3 Sep, 3 September, needed attention, before this feedback",
			"#19 (opens in a new tab)6 Sep, 6 September, needed attention, before this feedback",
		]);
		// The meter's sentence is its description and its tooltip, no longer a line under the row.
		const meter = canvas.getByRole("meter", { name: "Clean work in a row" });
		await expect(meter).toHaveAccessibleDescription(
			"Resolves on its own once three pieces of work in a row come back clean",
		);
		await expect(meter).toHaveAttribute("aria-valuetext", "None clean yet · 3 in a row resolve it");
		await expect(
			canvas.queryByText("Resolves on its own once three pieces of work in a row come back clean", {
				ignore: ".sr-only, .sr-only *",
			}),
		).toBeNull();
		// The rule waits behind the (i) beside the zone's title, with the card's own number.
		await expect(
			within(article).queryByText("Resolves when three pieces of reviewed work come back clean."),
		).toBeNull();
		// The (i) is named for its card, so a page of cards has one name per button.
		const ruleTip = within(article).getByRole("button", { name: /^How this feedback resolves: /u });
		await expect(
			within(article)
				.getByRole("heading", { name: "Work reviewed for this practice" })
				.compareDocumentPosition(ruleTip),
		).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
		await userEvent.click(ruleTip);
		const rulePanel = await screen.findByRole("dialog", { name: "How this feedback resolves" });
		await waitFor(async () => {
			await expect(rulePanel).toBeVisible();
		});
		await expect(rulePanel).toHaveTextContent(
			"Resolves when three pieces of reviewed work come back clean.",
		);
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => {
			await expect(
				screen.queryByRole("dialog", { name: "How this feedback resolves" }),
			).not.toBeInTheDocument();
		});
		// A keyboard reaches the meter, and focusing it opens the sentence as its tooltip.
		meter.focus();
		const tooltip = await screen.findByText(/Resolves on its own once three pieces/u, {
			selector: "[data-slot=tooltip-content], [data-slot=tooltip-content] *",
		});
		await expect(tooltip.closest("[data-slot=tooltip-content]")).toHaveAttribute("data-open");
		meter.blur();
		// Three hollow dots wait for the clean work, and only the last says "resolves".
		await expect(canvas.getAllByText("resolves")).toHaveLength(1);
		await expect(canvas.queryByText("next")).toBeNull();
		// The band has a rule below it as well as above, so it does not run into the progress.
		const band = within(article)
			.getByRole("heading", { name: "Next step" })
			.closest<HTMLElement>("[data-next-step]");
		if (!band) {
			throw new Error("Expected the next step's band.");
		}
		await expect(getComputedStyle(band).borderTopWidth).toBe("1px");
		await expect(getComputedStyle(band).borderBottomWidth).toBe("1px");
		// The call to action: a narrow accent strip on the left edge, as tall as the band, rather than a
		// border, so there is no mitred corner where it meets the rules.
		await expect(getComputedStyle(band).borderLeftWidth).toBe("0px");
		const edge = band.querySelector("[data-next-step-edge]");
		if (!edge) {
			throw new Error("Expected the next step's accent edge.");
		}
		await expect(edge.getBoundingClientRect().width).toBe(2);
		await expect(edge.getBoundingClientRect().height).toBe(band.clientHeight);
		// The order: what it is about, the next step, then the progress with the answers in it.
		const inOrder = [
			within(article).getByRole("heading", { name: args.card.headline }),
			within(article).getByRole("heading", { name: "Next step" }),
			within(article).getByRole("heading", { name: "Work reviewed for this practice" }),
			timeline,
			within(article).getByRole("group", { name: "Or mark it yourself" }),
			within(article).getByRole("group", { name: "Rate this feedback" }),
		];
		for (const [index, element] of inOrder.slice(1).entries()) {
			await expect(inOrder[index]?.compareDocumentPosition(element) ?? 0).toBe(
				Node.DOCUMENT_POSITION_FOLLOWING,
			);
		}
		// The reader's own answers write the registry's values.
		const response = within(canvas.getByRole("group", { name: "Or mark it yourself" }));
		await userEvent.click(response.getByRole("button", { name: "I fixed this" }));
		await expect(args.onResolve).toHaveBeenCalledWith("ADDRESSED");
		// The head names the practice as a control that opens it, large enough to press.
		const pill = canvas.getByRole("button", { name: "Scope the change to one concern" });
		await expectTouchTarget(pill);
		await userEvent.click(pill);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("scope-one-reviewable-change");
		// The group's icon stands beside the group's name, not inside the practice pill.
		const groupName = canvas.getByRole("button", { name: "Packaging work for review" });
		await expect(groupName.parentElement?.querySelector("svg")).not.toBeNull();
		await expect(pill.querySelector("svg")).toBeNull();
		await userEvent.click(groupName);
		await expect(args.onOpenGroup).toHaveBeenCalledWith("review-ready-work");
		// "Learn more about this practice" is a link-styled button.
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
		// The body's own list, not the timeline's, which is named.
		const bullets = canvas.getAllByRole("list").find((list) => !list.hasAttribute("aria-label"));
		if (!bullets) {
			throw new Error("Expected the body's bullet list");
		}
		const [pageSize, retries, ...rest] = within(bullets).getAllByRole("listitem");
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

/** The next step is Markdown too: a test or a command the composer names is code. */
export const MarkdownNextStep: Story = {
	args: {
		card: {
			...card,
			state: "open",
			nextStep: "Add a test to `PagingControllerTest` that fails when the page size is not `20`.",
		},
	},
	play: async ({ canvas }) => {
		const code = canvas.getByText("PagingControllerTest", { selector: "code" });
		await expect(code).toBeVisible();
		// The typography plugin draws a backtick on each side of inline code unless told not to.
		await expect(getComputedStyle(code, "::before").content).toBe("none");
	},
};

/**
 * Two of three clean: the "Open" badge, and the clean pieces join the timeline after the mark
 * "Feedback created", joined in green, and one hollow place waits at the end for the piece that
 * resolves it. The timeline spans its width, every piece evenly spaced along one thin line, each
 * its outcome's registry icon: a wrench where it needed attention, a check in a circle where it
 * came back clean.
 */
/**
 * The line to the places still missing keeps one even dash pattern across each gap: the two halves
 * of a joint meet in phase, gap against dash, so no longer dash sits in the middle.
 */
export const EvenDashes: Story = {
	args: { card: { ...card, state: "open", cleanWork: [] } },
	play: async ({ canvasElement }) => {
		const halves = [...canvasElement.querySelectorAll<HTMLElement>("[data-joint='missing']")];
		await expect(halves.length).toBeGreaterThan(1);
		for (const half of halves) {
			await expect(getComputedStyle(half).backgroundImage).toContain("repeating-linear-gradient");
			await expect(getComputedStyle(half).borderTopStyle).not.toBe("dashed");
		}
	},
};

export const TwoOfThreeClean: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("New")).toBeNull();
		await expect(canvas.getByText("Open")).toBeVisible();
		const meter = canvas.getByRole("meter", { name: "Clean work in a row" });
		await expect(meter).toHaveAttribute("aria-valuenow", "2");
		await expect(meter).toHaveAttribute("aria-valuemax", "3");
		await expect(canvas.getByRole("meter", { name: "Clean work in a row" })).toHaveAttribute(
			"aria-valuetext",
			"2 clean in a row · 1 more resolves it",
		);
		const timeline = within(canvas.getByRole("list", { name: "Where it happened" }));
		await expect(
			timeline
				.getAllByRole("link")
				.map((link) => link.textContent.replace(" (opens in a new tab)", "")),
		).toStrictEqual(["#17", "#20", "#19", "#21", "#22"]);
		await expect(timeline.getAllByRole("listitem").at(-1)).toHaveTextContent(
			/#22.*11 September, clean$/u,
		);
		// The missing place is drawn, not read: the meter already says it.
		await expect(canvas.getByText("resolves")).toBeVisible();
		await expect(timeline.getAllByRole("listitem")).toHaveLength(5);
		// The line spans the width it has, and its six places share it evenly. The mark for the
		// feedback stands between the evidence and the clean work, in only the room its caption needs.
		const list = canvas.getByRole("list", { name: "Where it happened" });
		const box = list.parentElement;
		if (!box) {
			throw new Error("Expected the timeline's box.");
		}
		await expect(list.getBoundingClientRect().width).toBeGreaterThanOrEqual(box.clientWidth - 8);
		const places = [...list.querySelectorAll<HTMLElement>(":scope > li")];
		await expect(places.findIndex((place) => Object.hasOwn(place.dataset, "feedbackCreated"))).toBe(
			3,
		);
		await expect(canvas.getByText("Feedback created")).toBeVisible();
		const widths = places
			.filter((place) => !Object.hasOwn(place.dataset, "feedbackCreated"))
			.map((place) => place.getBoundingClientRect().width);
		await expect(widths).toHaveLength(6);
		await expect(Math.max(...widths) - Math.min(...widths)).toBeLessThan(1);
		// Each piece is its outcome's icon on the line: a check in a circle for clean, a wrench for
		// needed attention.
		const markers = [...list.querySelectorAll<SVGElement>("svg[data-outcome]")];
		await expect(markers.map((marker) => marker.dataset.outcome)).toStrictEqual([
			"NOT_MET",
			"NOT_MET",
			"NOT_MET",
			"MET",
			"MET",
		]);
		await expect(markers.map((marker) => marker.classList.contains("lucide-wrench"))).toStrictEqual(
			[true, true, true, false, false],
		);
		await expect(
			markers.map((marker) => marker.classList.contains("lucide-circle-check")),
		).toStrictEqual([false, false, false, true, true]);
		// The registry's icons in its tones, the same the profile and the admin pages draw, with no
		// ground behind them.
		await expect(
			markers.map((marker) => marker.classList.contains("text-destructive")),
		).toStrictEqual([true, true, true, false, false]);
		await expect(markers.map((marker) => marker.classList.contains("text-success"))).toStrictEqual([
			false,
			false,
			false,
			true,
			true,
		]);
		for (const marker of markers) {
			await expect(marker.closest(".rounded-full")).toBeNull();
		}
		// The card's own edge is the one strong line, with a faint shadow; the rules inside are lighter.
		const article = canvas.getByRole("article");
		const edge = getComputedStyle(article);
		await expect(edge.boxShadow).not.toBe("none");
		const footer = canvas.getByRole("group", { name: "Rate this feedback" }).parentElement;
		if (!footer) {
			throw new Error("Expected the card's footer.");
		}
		await expect(getComputedStyle(footer).borderTopColor).not.toBe(edge.borderTopColor);
	},
};

/** Nine pieces of work: seven that needed attention in August, then two clean in September. */
const longHistory = [11, 12, 13, 14, 15, 16, 17].map((number, index) => ({
	ref: pullRequest(number),
	date: inStoryYear(`08-${String(10 + index * 3).padStart(2, "0")}T00:00`),
	outcome: "NOT_MET" as const,
}));

/**
 * A long history: nine pieces of reviewed work, and the timeline shows the five newest, never
 * more. The four older ones fold into one short "…" stretch at the start of the line, with no count, and
 * the one clean piece still needed waits as a hollow dot after the newest.
 */
export const LongHistory: Story = {
	args: {
		card: { ...card, state: "open", reviewedWork: longHistory, cleanWork: twoClean },
	},
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list", { name: "Where it happened" });
		const timeline = within(list);
		// Five pieces, the newest, oldest first.
		await expect(
			timeline
				.getAllByRole("link")
				.map((link) => link.textContent.replace(" (opens in a new tab)", "")),
		).toStrictEqual(["#15", "#16", "#17", "#21", "#22"]);
		await expect(list.querySelectorAll("svg[data-outcome]")).toHaveLength(5);
		// The older four are one place at the start, not a dot each.
		const items = timeline.getAllByRole("listitem");
		await expect(items).toHaveLength(6);
		await expect(items[0]).toHaveTextContent("Earlier work not shown");
		await expect(items[0]).toHaveAttribute("data-earlier-work");
		await expect(canvas.queryByText(/four earlier|4 earlier/u)).toBeNull();
		// One clean piece still needed: one hollow dot, the one that resolves.
		await expect(canvas.getAllByText("resolves")).toHaveLength(1);
		await expect(canvas.getByRole("meter", { name: "Clean work in a row" })).toHaveAttribute(
			"aria-valuetext",
			"2 clean in a row · 1 more resolves it",
		);
	},
};

/**
 * "Work reviewed for this practice" on a card of its full width: the left side is the timeline, the
 * right side one compact block, well apart from it. In the block the meter's muted line stands above
 * its track, the label "Or mark it yourself" follows, then the two answers in a row of one height; the
 * block's left edges align, and the block stands at the zone's right edge. A card narrower than
 * 36rem stacks the block under the timeline, as `Mobile` shows.
 */
export const ProgressSides: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	play: async ({ canvas, canvasElement }) => {
		const box = canvas.getByRole("list", { name: "Where it happened" }).parentElement;
		const block = canvasElement.querySelector("[data-progress-status]");
		if (!box || !block) {
			throw new Error("Expected the timeline's box and the status block.");
		}
		const line = box.getBoundingClientRect();
		const side = block.getBoundingClientRect();
		const meterElement = canvas.getByRole("meter", { name: "Clean work in a row" });
		const meter = meterElement.getBoundingClientRect();
		const label = canvas.getByText("Or mark it yourself").getBoundingClientRect();
		const answers = canvas.getByRole("group", { name: "Or mark it yourself" });
		const buttons = within(answers)
			.getAllByRole("button")
			.map((button) => button.getBoundingClientRect());
		// The block stands right of the timeline with room between, at the zone's right edge.
		await expect(side.left - line.right).toBeGreaterThanOrEqual(32);
		const zone = block.parentElement?.getBoundingClientRect();
		await expect(Math.abs((zone?.right ?? 0) - side.right)).toBeLessThan(1);
		// Meter, label, answers: under each other, their left edges on one line.
		await expect(label.top).toBeGreaterThanOrEqual(meter.bottom);
		await expect(answers.getBoundingClientRect().top).toBeGreaterThanOrEqual(label.bottom);
		for (const left of [label.left, buttons[0]?.left ?? 0]) {
			await expect(Math.abs(left - meter.left)).toBeLessThan(1);
		}
		// The count is one muted line above the track, set like "Or mark it yourself".
		const value = within(meterElement).getByText("2 clean in a row · 1 more resolves it");
		const answersLabel = canvas.getByText("Or mark it yourself");
		await expect(getComputedStyle(value).fontSize).toBe(getComputedStyle(answersLabel).fontSize);
		await expect(getComputedStyle(value).color).toBe(getComputedStyle(answersLabel).color);
		const track = value.nextElementSibling?.getBoundingClientRect();
		await expect(track?.width ?? 0).toBeGreaterThanOrEqual(96);
		await expect(track?.top ?? 0).toBeGreaterThanOrEqual(value.getBoundingClientRect().bottom);
		// The two answers in one row, of one height.
		await expect(buttons).toHaveLength(2);
		await expect(Math.abs((buttons[0]?.top ?? 0) - (buttons[1]?.top ?? 1))).toBeLessThan(1);
		await expect(buttons[0]?.height).toBe(buttons[1]?.height);
		// The line still opens at its start, as it fits its side without scrolling.
		await expect(box.scrollWidth).toBe(box.clientWidth);
	},
};

/**
 * The work resolved it: the card wears the success colour's wash, the badge, the green tick leading
 * the band and the filled meter carry the state, the closing sentence stands under "Your last
 * reviewed work", and the footer dates the resolution.
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
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Resolved")).toBeVisible();
		await expect(canvas.queryByText("Open")).toBeNull();
		await expect(canvas.getByText("3 clean in a row")).toBeVisible();
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
		// The closing sentence stands under the zone's title, in place of the rule.
		await expect(canvas.queryByText(/^Resolves when/u)).toBeNull();
		// The work's resolution is not the reader's to take back, so there is no answer to give.
		await expect(canvas.queryByRole("group", { name: "Or mark it yourself" })).toBeNull();
	},
};

/**
 * The reader's answer is a claim the next work confirms, not a resolution, so the card closes
 * without the success wash. "I fixed this" stays pressed, and its tooltip says a second press
 * takes it back.
 */
export const MarkedByYou: Story = {
	args: {
		card: {
			...card,
			state: "marked",
			cleanWork: twoClean,
			condition: [text("Marked as addressed on 9 September. Your next work confirms it.")],
			timestamp: inStoryYear("09-09T16:05"),
		},
		resolution: "ADDRESSED",
	},
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("Marked by you")).toBeVisible();
		await expect(canvas.queryByText("Resolved")).toBeNull();
		await expect(canvas.getByText("Marked 9 September")).toBeVisible();
		await expect(
			canvas.getByText("Marked as addressed on 9 September. Your next work confirms it."),
		).toBeVisible();
		await expect(canvas.getByText("2 clean in a row · 1 more resolves it")).toBeVisible();
		const fixed = canvas.getByRole("button", { name: "I fixed this" });
		await expect(fixed).toHaveAttribute("aria-pressed", "true");
		await expect(fixed).toHaveAccessibleDescription(/Press it again to take it back\.$/u);
		fixed.focus();
		await expect(
			await screen.findByText(/Press it again to take it back\.$/u, {
				selector: "[data-slot=tooltip-content]",
			}),
		).toHaveAttribute("data-open");
		fixed.blur();
		await expect(canvas.getByRole("button", { name: "Does not fit my work" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await userEvent.click(fixed);
		await expect(args.onResolve).toHaveBeenCalledWith("ADDRESSED");
	},
};

/**
 * The two answers in the student's words under the label "Or mark it yourself". What each does is
 * no visible line: its tooltip says it, and a screen reader hears it as the button's description.
 * They write the registry's values, `ADDRESSED` and `NOT_APPLICABLE`.
 */
export const ResponseLabels: Story = {
	args: { card: { ...card, state: "open" } },
	play: async ({ args, canvas }) => {
		const answers = within(canvas.getByRole("group", { name: "Or mark it yourself" }));
		const fixed = answers.getByRole("button", { name: "I fixed this" });
		const doesNotFit = answers.getByRole("button", { name: "Does not fit my work" });
		await expect(fixed).toHaveAccessibleDescription(
			"The card moves to Marked by you, and your next work confirms it.",
		);
		await expect(doesNotFit).toHaveAccessibleDescription(
			"The card moves to Marked by you, and a later review of the same work does not send you this point again.",
		);
		// No visible explanation: the effect waits in each button's tooltip.
		await expect(
			canvas.queryByText(/a later review of the same work does not send you/u, {
				ignore: ".sr-only",
			}),
		).toBeNull();
		await userEvent.hover(doesNotFit);
		const tooltip = await screen.findByText(/a later review of the same work does not send you/u, {
			selector: "[data-slot=tooltip-content]",
		});
		await expect(tooltip).toHaveAttribute("data-open");
		await userEvent.unhover(doesNotFit);
		await userEvent.click(fixed);
		await expect(args.onResolve).toHaveBeenLastCalledWith("ADDRESSED");
		await userEvent.click(doesNotFit);
		await expect(args.onResolve).toHaveBeenLastCalledWith("NOT_APPLICABLE");
	},
};

export const Ratings: Story = {
	args: { card: { ...card, state: "open" }, usefulness: "HELPFUL" },
	play: async ({ args, canvas }) => {
		const ratings = within(canvas.getByRole("group", { name: "Rate this feedback" }));
		const helpful = ratings.getByRole("button", { name: "Helpful" });
		await expect(helpful).toHaveAttribute("aria-pressed", "true");
		await expectTouchTarget(helpful);
		// Quieter than the action: no ground at rest.
		const notHelpful = ratings.getByRole("button", { name: "Not helpful" });
		await expect(getComputedStyle(notHelpful).backgroundColor).toBe("rgba(0, 0, 0, 0)");
		await userEvent.click(notHelpful);
		await expect(args.onRate).toHaveBeenCalledWith("UNHELPFUL");
		await userEvent.click(helpful);
		await expect(args.onRate).toHaveBeenLastCalledWith("HELPFUL");
		await userEvent.click(ratings.getByRole("button", { name: "Disagree" }));
		await expect(args.onDisagree).toHaveBeenCalledOnce();
		// The tooltip says what a rating means.
		await userEvent.hover(notHelpful);
		const tooltip = await screen.findByText(/This did not tell you anything you could act on/u, {
			selector: "[data-slot=tooltip-content]",
		});
		await expect(tooltip).toHaveAttribute("data-open");
	},
};

export const DisagreeOpen: Story = {
	args: { card: { ...card, state: "open" }, openBand: "disagreement" },
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: "What is wrong in this feedback?" });
		await expect(field).toBeRequired();
		await expect(field).toHaveAccessibleDescription(/Workspace admins read your sentence/u);
		await userEvent.type(field, "#17 split the refactor out already.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSendDisagreement).toHaveBeenCalledWith(
			"#17 split the refactor out already.",
		);
	},
};

export const Disagreed: Story = {
	args: { card: { ...card, state: "open" }, resolution: "DISAGREE" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Disagree" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.getByText("Open")).toBeVisible();
	},
};

/** "Helpful" opens the note band: an optional line, sent or skipped. */
export const HelpfulNoteOpen: Story = {
	args: { card: { ...card, state: "open" }, usefulness: "HELPFUL", openBand: "comment" },
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: "What worked about this feedback?" });
		await expect(field).toBeVisible();
		await expect(field).not.toBeRequired();
		await userEvent.type(field, "The split into two merge requests was the right call.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSendComment).toHaveBeenCalledWith(
			"The split into two merge requests was the right call.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(args.onSkipComment).toHaveBeenCalledOnce();
	},
};

export const NotHelpfulNoteOpen: Story = {
	args: { card: { ...card, state: "open" }, usefulness: "UNHELPFUL", openBand: "comment" },
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: "Why was this not helpful?" });
		await expect(field).not.toBeRequired();
		await userEvent.type(field, "Each of these was already one concern.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSendComment).toHaveBeenCalledWith("Each of these was already one concern.");
	},
};

/** Whether a press at the middle of `element` lands on it, rather than on a layer over it. */
function takesItsOwnPress(element: HTMLElement): boolean {
	const box = element.getBoundingClientRect();
	const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
	return hit !== null && element.contains(hit);
}

/**
 * The card as one target: a press anywhere on it opens the practice through "Learn more
 * about this practice", which stretches over the card. Every control inside still takes its own
 * press, and none of them opens the practice.
 */
export const CardLink: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	play: async ({ args, canvas }) => {
		const article = canvas.getByRole("article", { name: args.card.headline });
		// A press on the text lands on the card's link, which opens the practice.
		const heading = within(article).getByRole("heading", { name: args.card.headline });
		const box = heading.getBoundingClientRect();
		const hit = document.elementFromPoint(box.left + 8, box.top + box.height / 2);
		if (!(hit instanceof HTMLElement)) {
			throw new Error("Expected an element over the heading.");
		}
		await expect(hit).toHaveAccessibleName("Learn more about this practice");
		await userEvent.click(hit);
		await expect(args.onLearnMore).toHaveBeenCalledOnce();
		// The inner controls lie above the card's link.
		const controls = [
			canvas.getByRole("button", { name: "Scope the change to one concern" }),
			canvas.getByRole("button", { name: "Packaging work for review" }),
			canvas.getByRole("button", { name: "Helpful" }),
			canvas.getByRole("button", { name: "Disagree" }),
			...within(canvas.getByRole("list", { name: "Where it happened" })).getAllByRole("link"),
			...within(canvas.getByRole("group", { name: "Or mark it yourself" })).getAllByRole("button"),
		];
		for (const control of controls) {
			await expect(takesItsOwnPress(control)).toBe(true);
		}
		await userEvent.click(canvas.getByRole("button", { name: "Helpful" }));
		await expect(args.onRate).toHaveBeenCalledWith("HELPFUL");
		await userEvent.click(canvas.getByRole("button", { name: "Disagree" }));
		await expect(args.onDisagree).toHaveBeenCalledOnce();
		await userEvent.click(canvas.getByRole("button", { name: "Scope the change to one concern" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("scope-one-reviewable-change");
		await expect(args.onLearnMore).toHaveBeenCalledOnce();
	},
};

/**
 * The hover state: the card takes a light tint and "Learn more about this practice" turns to the
 * accent, underlined as the app's links are, with its arrow. Hover the card in the canvas to see it.
 */
export const Hover: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	play: async ({ canvas }) => {
		// The link is there at rest too, never shown on hover alone, and not underlined until the card
		// is hovered: then it takes the app's link underline, as the work links do.
		const learnMore = canvas.getByRole("button", { name: "Learn more about this practice" });
		await expect(learnMore).toBeVisible();
		await expect(getComputedStyle(learnMore).textDecorationLine).toBe("none");
		await expect(learnMore).toHaveClass("group-hover/card:underline");
		const workLink = canvas.getAllByRole("link", { name: /^#19/u })[0];
		if (!workLink) {
			throw new Error("Expected #19 in the explanation.");
		}
		for (const property of ["textUnderlineOffset", "textDecorationThickness"] as const) {
			await expect(getComputedStyle(learnMore)[property]).toBe(
				getComputedStyle(workLink)[property],
			);
		}
		await expect(learnMore).toHaveAttribute("data-card-link");
	},
};

/**
 * The keyboard: the card's link is one tab stop, and focusing it shows the card the way a
 * hover does, with the focus ring around the whole card and the link underlined. Enter opens the
 * practice.
 */
export const KeyboardFocus: Story = {
	args: { card: { ...card, state: "open" } },
	play: async ({ args, canvas }) => {
		const article = canvas.getByRole("article", { name: args.card.headline });
		const link = canvas.getByRole("button", { name: "Learn more about this practice" });
		// Tab from the last control before it in the reading order: the explanation's work links.
		const before = within(article).getAllByRole("link", { name: /^#20/u })[0];
		if (!before) {
			throw new Error("Expected #20 in the explanation.");
		}
		before.focus();
		await userEvent.tab();
		await expect(link).toHaveFocus();
		// Focused, the link is underlined as the app's links are.
		await expect(getComputedStyle(link).textDecorationLine).toBe("underline");
		// The card shows the hover's tint and its own focus ring.
		await expect(link).toHaveAttribute("data-card-link");
		await expect(getComputedStyle(article).boxShadow).not.toBe("none");
		await userEvent.keyboard("{Enter}");
		await expect(args.onLearnMore).toHaveBeenCalledOnce();
	},
};

/**
 * Clean and needed-attention work mixed on one line, in time order whatever order it was given in.
 * The icon tells them apart on screen, and each place also says it in words for a screen reader.
 * All four pieces came before the feedback, so the mark "Feedback created" closes the evidence and
 * the two clean pieces are muted: they do not count, and the meter reads "None clean yet".
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
				{ ref: pullRequest(16), date: inStoryYear("08-24T00:00"), outcome: "NOT_MET" },
				{ ref: pullRequest(19), date: inStoryYear("09-06T00:00"), outcome: "NOT_MET" },
				{ ref: pullRequest(20), date: inStoryYear("09-03T00:00"), outcome: "MET" },
				{
					ref: pullRequest(21),
					date: inStoryYear("09-08T00:00"),
					outcome: "MET",
				},
			],
			nextStep:
				"Before the file list, write one paragraph on the problem and the decision you took.",
		},
	},
	play: async ({ canvas }) => {
		// Oldest first whatever order the work was given in: #16 on 24 Aug, #20 on 3 Sep, #19 on
		// 6 Sep, #21 on 8 Sep.
		const list = canvas.getByRole("list", { name: "Where it happened" });
		await expect(linkedWork(list)).toStrictEqual(["#16", "#20", "#19", "#21"]);
		await expect(outcomesOnTheLine(list)).toStrictEqual(["NOT_MET", "MET", "NOT_MET", "MET"]);
		await expect(within(list).getAllByRole("listitem")[1]).toHaveTextContent(
			/, clean, before this feedback$/u,
		);
		await expect(within(list).getAllByRole("listitem")[2]).toHaveTextContent(
			/, needed attention, before this feedback$/u,
		);
		// Clean work before the feedback is muted, not the green of the work that counts.
		const markers = [...list.querySelectorAll<SVGElement>("svg[data-outcome]")];
		await expect(markers.map((marker) => marker.classList.contains("text-success"))).toStrictEqual([
			false,
			false,
			false,
			false,
		]);
		await expect(
			markers.map((marker) => marker.classList.contains("text-muted-foreground")),
		).toStrictEqual([false, true, false, true]);
		// The mark for the feedback follows the evidence, and the places still missing follow it.
		const places = [...list.querySelectorAll<HTMLElement>(":scope > li")];
		await expect(places.findIndex((place) => Object.hasOwn(place.dataset, "feedbackCreated"))).toBe(
			4,
		);
		await expect(canvas.getByRole("meter", { name: "Clean work in a row" })).toHaveAttribute(
			"aria-valuetext",
			"None clean yet · 3 in a row resolve it",
		);
	},
};

/** Work at GitLab: the timeline and the explanation read the wire's own labels, with their sigil. */
export const GitLabWork: Story = {
	args: {
		card: {
			...card,
			state: "open",
			reviewedWork: card.reviewedWork.map((piece) => ({
				...piece,
				ref: { ...piece.ref, provider: "GITLAB", label: piece.ref.label.replace("#", "!") },
			})),
		},
	},
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list", { name: "Where it happened" });
		await expect(linkedWork(list)).toStrictEqual(["!17", "!20", "!19"]);
		// The head follows the provider: merge requests at GitLab.
		await expect(canvas.getByText("Recurring in three merge requests")).toBeVisible();
	},
};

/**
 * Work of more than one kind — a pull request and an issue, with clean work in a conversation —
 * stands on one line in time order; the conversation has no page, so it is named, not linked.
 */
export const MixedWork: Story = {
	args: {
		card: {
			...card,
			state: "open",
			reviewedWork: [
				{ ref: pullRequest(17), date: inStoryYear("08-28T00:00"), outcome: "NOT_MET" },
				{ ref: issue(13), date: inStoryYear("09-03T00:00"), outcome: "NOT_MET" },
			],
			cleanWork: [{ ref: conversation("#releases"), date: inStoryYear("09-10T00:00") }],
		},
	},
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list", { name: "Where it happened" });
		await expect(within(list).getAllByRole("listitem")).toHaveLength(3);
		await expect(linkedWork(list)).toStrictEqual(["#17", "#13"]);
		await expect(within(list).getByText("#releases")).toBeVisible();
		// Two kinds of work: the head names neither, and counts only the work the feedback rests on.
		await expect(canvas.getByText("Recurring in two pieces of reviewed work")).toBeVisible();
	},
};

export const InterleavedReviewTimes: Story = {
	args: {
		card: { ...card, preparedAt: inStoryYear("09-02T00:00"), cleanWork: [clean(21, "09-04")] },
	},
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list", { name: "Where it happened" });
		await expect(linkedWork(list)).toStrictEqual(["#17", "#20", "#21", "#19"]);
		await expect(within(list).getByText("Feedback created")).toBeVisible();
		await expect(within(list).getAllByRole("listitem")[3]).not.toHaveTextContent(
			"before this feedback",
		);
		await expect([...list.querySelectorAll("[data-before-feedback]")]).toHaveLength(1);
	},
};

export const OnlyReviewsAfterCreation: Story = {
	args: { card: { ...card, preparedAt: inStoryYear("08-01T00:00") } },
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list", { name: "Where it happened" });
		await expect(within(list).queryByText("Feedback created")).toBeNull();
		await expect([...list.querySelectorAll("[data-before-feedback]")]).toHaveLength(0);
		await expect(list).not.toHaveTextContent("before this feedback");
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
 * Work the provider exposes no page for — a Slack channel — is named in the body and on the
 * timeline alike as a word: no link, and none of the link's hover either.
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
					date: inStoryYear("08-12T00:00"),
					outcome: "NOT_MET",
				},
			],
			cleanWork: [{ ref: conversation("#releases"), date: inStoryYear("09-10T00:00") }],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
		await expect(
			within(canvas.getByRole("list", { name: "Where it happened" })).getAllByRole("listitem"),
		).toHaveLength(2);
		// The body names it in its own words, since only a number carries an address to link to.
		await expect(canvas.getByText(/^In #backend-review the outage/u)).toBeVisible();
	},
};

/**
 * The practice's review rules changed after the card was written: it closes unresolved, keeps its
 * evidence, wears no wash, draws no clean-work meter, and says why under "Work reviewed for this practice".
 */
export const Closed: Story = {
	args: {
		card: {
			...card,
			state: "closed",
			cleanWork: twoClean,
			condition: [text("Closed on 9 September · the practice’s review rules changed")],
			timestamp: inStoryYear("09-09T09:00"),
			preparedAt: inStoryYear("09-08T09:00"),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Closed")).toBeVisible();
		await expect(canvas.queryByText("Resolved")).toBeNull();
		await expect(canvas.queryByRole("meter")).toBeNull();
		await expect(canvas.getByText("Closed 9 September")).toBeVisible();
		await expect(
			canvas.getByText("Closed on 9 September · the practice’s review rules changed"),
		).toBeVisible();
		// Closed unresolved, and no answer reopens it.
		await expect(canvas.queryByRole("group", { name: "Or mark it yourself" })).toBeNull();
	},
};

/**
 * Withdrawn by a workspace admin: the notice alone, with none of the old words, evidence, next step, meter
 * or answer buttons, and nothing that reads as a resolution.
 */
export const Withdrawn: Story = {
	args: {
		card: {
			...card,
			headline: card.practiceName,
			body: "",
			nextStep: "",
			condition: [],
			reviewedWork: [],
			cleanWork: [],
			state: "withdrawn",
			timestamp: inStoryYear("09-12T09:00"),
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "This feedback was withdrawn" }),
		).toBeVisible();
		await expect(canvas.getByText("Withdrawn 12 September")).toBeVisible();
		await expect(canvas.queryByText("Next step")).toBeNull();
		await expect(canvas.queryByRole("meter")).toBeNull();
		await expect(canvas.queryByText("Resolved")).toBeNull();
		await expect(canvas.queryByRole("group", { name: "Or mark it yourself" })).toBeNull();
		await expect(canvas.queryByRole("list", { name: "Where it happened" })).toBeNull();
		await expect(canvas.queryByText(/^Recurring in /u)).toBeNull();
		await expect(
			canvas.queryByRole("button", { name: "Learn more about this practice" }),
		).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Helpful" })).toBeNull();
	},
};

/** Every response control waits, and only the control that wrote says "Saving…". Send waits without claiming to send. */
export const RatingPending: Story = {
	args: {
		card: { ...card, state: "open" },
		usefulness: "HELPFUL",
		openBand: "comment",
		isPending: true,
		saving: "rating",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Not helpful" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Send" })).toBeDisabled();
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
		await expect(canvas.queryByRole("button", { name: "I fixed this" })).toBeNull();
		await expect(
			canvas.queryByRole("button", { name: "Learn more about this practice" }),
		).toBeNull();
		await expect(
			canvas.queryByRole("button", { name: "Scope the change to one concern" }),
		).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Packaging work for review" })).toBeNull();
	},
};

/**
 * On a phone: the zones stack and the page does not scroll sideways; the timeline scrolls in
 * its own box, opened at the newest work, and the meter and the answers stack under it, full width.
 */
export const Mobile: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 390] },
	},
	play: async (context) => {
		await expectNoPageOverflow();
		const box = context.canvas.getByRole("list", { name: "Where it happened" }).parentElement;
		if (!box) {
			throw new Error("Expected the timeline's box.");
		}
		await expect(box.scrollWidth).toBeGreaterThan(box.clientWidth);
		await expect(box.scrollLeft).toBeGreaterThan(0);
		// The status and the action stack under the timeline.
		const line = box.getBoundingClientRect();
		const meter = context.canvas.getByRole("meter", { name: "Clean work in a row" });
		const answers = context.canvas.getByRole("group", { name: "Or mark it yourself" });
		await expect(meter.getBoundingClientRect().top).toBeGreaterThanOrEqual(line.bottom);
		await expect(answers.getBoundingClientRect().top).toBeGreaterThanOrEqual(
			meter.getBoundingClientRect().bottom,
		);
		// Stacked, the block takes the zone's full width, the meter's bar with it.
		const block = context.canvasElement.querySelector("[data-progress-status]");
		const zone = block?.parentElement?.getBoundingClientRect();
		await expect(
			Math.abs((block?.getBoundingClientRect().width ?? 0) - (zone?.width ?? 1)),
		).toBeLessThan(1);
		await expect(Math.abs(meter.getBoundingClientRect().width - (zone?.width ?? 1))).toBeLessThan(
			1,
		);
	},
};

/** Dark theme: the explanation, the band and the timeline keep their contrast. */
export const Dark: Story = {
	args: { card: { ...card, state: "open", cleanWork: twoClean } },
	globals: { theme: "dark" },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("article", { name: args.card.headline })).toBeVisible();
	},
};

/** Hidden from assistive technology: the list's `aria-busy` says it is loading. */
export const Loading: Story = {
	render: () => <PracticeFeedbackCardSkeleton />,
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("article")).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

export const UnverifiedHistory: Story = {
	args: { card: { ...card, unverifiedHistory: true } },
	play: async ({ canvas }) => {
		canvas.getByText(
			"Unverified history from an earlier review. It does not show whether the current standard is met.",
		);
	},
};
