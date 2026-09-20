import type { ReviewFeedback } from "@/api/types.gen";
import { hasText } from "@/lib/text";

/**
 * The opening words of a piece of feedback, as a line of prose a row can be told apart by.
 *
 * `bodyPreview` is a fixed-character cut of the Markdown note the developer receives, so it usually
 * ends somewhere inside the first fenced code quote. Printed verbatim a row title reads as markup, a
 * language tag and half a statement. This flattens it to the sentence a person would read out: code
 * fences go entirely — a fragment of somebody's Java is never the useful summary of a note about
 * their Java — as do rules and list bullets, while inline markers are unwrapped so the words survive.
 *
 * The trailing ellipsis marks both cuts the text has been through, the server's and this function's.
 * Without it a preview that stops mid-clause reads as feedback that was itself truncated.
 *
 * Returns `undefined` for the two states with no prose to show: no body at all — conversation
 * feedback is recorded before anything is composed — and a preview that is nothing but a code quote.
 * They read differently to an operator, so the caller tells them apart on `bodyPreview` rather than
 * printing one sentence for both.
 */
export function feedbackPreviewText(
	feedback: Pick<ReviewFeedback, "bodyPreview" | "bodyTruncated">,
): string | undefined {
	const source = feedback.bodyPreview;
	if (!hasText(source)) {
		return undefined;
	}

	const { text, dropped } = flattenMarkdown(source);
	if (!text) {
		return undefined;
	}
	// A full stop immediately before the ellipsis reads as a typo rather than as a continuation.
	return feedback.bodyTruncated || dropped ? `${text.replace(/\.$/u, "")}…` : text;
}

interface Flattened {
	text: string;
	/** Whether anything was left out, as opposed to merely being unwrapped. */
	dropped: boolean;
}

function flattenMarkdown(source: string): Flattened {
	const lines = source.split("\n");
	const kept: string[] = [];
	let dropped = false;
	let insideFence = false;
	/** The "You wrote:" line dropped with its block, held in case the block was all there was. */
	let leadIn: string | undefined;

	for (const line of lines) {
		if (line.trimStart().startsWith("```")) {
			// An opening fence starts a block we skip; a closing one ends it. A preview cut mid-block
			// never sees the closing fence, which is why `insideFence` also ends the loop's output.
			// The line introducing the block goes with it: "You wrote:" followed by the *next* paragraph
			// instead of the code claims the developer wrote something they did not.
			if (!insideFence && kept.at(-1)?.endsWith(":") === true) {
				leadIn = kept.pop();
			}
			insideFence = !insideFence;
			dropped = true;
			continue;
		}
		if (insideFence) {
			continue;
		}
		const trimmed = line.trim();
		if (!trimmed) {
			continue;
		}
		// A horizontal rule separates two observations; in one line of prose it is a false sentence break.
		if (/^(?<rule>[-*_])\k<rule>{2,}$/u.test(trimmed.replaceAll(/\s/gu, ""))) {
			dropped = true;
			continue;
		}
		kept.push(
			inlineToText(trimmed.replace(/^#{1,6}\s+/u, "").replace(/^(?:[-*+]|\d+\.|>)\s+/u, "")),
		);
	}

	// Dropping the lead-in is right when prose follows the block and wrong when nothing does: where
	// the cut landed inside the first fence, that one line is every word of prose there is, and
	// popping it would report a note that has a body as having none.
	if (kept.length === 0 && hasText(leadIn)) {
		kept.push(leadIn);
	}

	return { text: kept.join(" ").replaceAll(/\s+/gu, " ").trim(), dropped };
}

/**
 * Link text is kept and the target dropped: a URL in a two-line preview is the least readable thing
 * that could occupy it, and the row already links somewhere of its own.
 */
function inlineToText(line: string): string {
	return line
		.replaceAll(/!\[[^\]]*\]\([^)]*\)/gu, "")
		.replaceAll(/\[(?<text>[^\]]+)\]\([^)]*\)/gu, "$<text>")
		.replaceAll(/`+/gu, "")
		.replaceAll(/(?<mark>\*\*|__)(?<text>.+?)\k<mark>/gu, "$<text>")
		.replaceAll(/(?<![\w*])[*_](?=\S)(?<text>.+?)(?<=\S)[*_](?![\w*])/gu, "$<text>");
}
