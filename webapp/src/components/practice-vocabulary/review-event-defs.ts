import { ITEM_KIND, type ItemProvider, itemKindNoun } from "@/lib/item-kinds";

/**
 * What the reader does, or what happens to their work, when an item event starts a review: the
 * end of "Hephaestus reviews your work when …". An event this build does not know says nothing,
 * rather than a wire id.
 */
const PHRASES: Record<string, (provider?: ItemProvider) => string> = {
	"scm.pull_request.opened": (provider) =>
		`you open a ${itemKindNoun(ITEM_KIND.pullRequest, 1, provider)}`,
	"scm.pull_request.ready": (provider) =>
		`you mark a ${itemKindNoun(ITEM_KIND.pullRequest, 1, provider)} ready for review`,
	"scm.pull_request.synchronized": (provider) =>
		`you push new commits to a ${itemKindNoun(ITEM_KIND.pullRequest, 1, provider)}`,
	"scm.pull_request.merged": (provider) =>
		`a ${itemKindNoun(ITEM_KIND.pullRequest, 1, provider)} of yours is merged`,
	"scm.pull_request_review.submitted": () => "you submit a review",
	"scm.issue.opened": () => "you open an issue",
	"scm.issue.updated": () => "you update an issue",
	"scm.issue.closed": () => "an issue of yours is closed",
	"chat.thread.settled": () => "a Slack thread you took part in goes quiet",
	"docs.document.published": () => "you publish a document",
	"docs.document.updated": () => "you update a document",
};

/** The phrases for the events that start a review, in the server's order, without unknown ones. */
export function reviewEventPhrases(events: readonly string[], provider?: ItemProvider): string[] {
	return events.flatMap((event) => {
		const phrase = PHRASES[event]?.(provider);
		return phrase === undefined ? [] : [phrase];
	});
}
