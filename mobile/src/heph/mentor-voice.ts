import type { HephAccess } from "./use-heph-access";

/**
 * What Heph says, in its own voice, on the surfaces where it mentors: built only from what the app has
 * loaded. It never interprets feedback, invents an insight, calls anything new or unread, praises, or
 * implies that no feedback means the work was reviewed or is going well. Heph points to the feedback
 * and offers to talk it through, but is never its author, and says nothing about where it came from that
 * the list does not.
 *
 * Heph speaks only where it can talk with the person. Anywhere its access is off, denied, unreachable or
 * still being checked, the screen keeps the system's own words.
 */
export function speaks(access: HephAccess): boolean {
	return access === "available";
}

/** Heph's note at the top of the practice feedback list, about the feedback it holds. */
export function feedbackNote(count: number): string {
	if (count === 0) {
		return "There’s no feedback for you here yet. It shows up here when there’s something in your work worth your attention. Until then, we can talk about anything on your mind.";
	}
	if (count === 1) {
		return "There’s one piece of feedback on your work. We can talk through what to try next.";
	}
	return `There are ${count} pieces of feedback on your work. Start with whichever matters most to you, and we can talk through what to try next.`;
}

/** The same count in the system's words, where Heph cannot speak. */
export function feedbackCount(count: number): string {
	return count === 1 ? "1 piece of feedback" : `${count} pieces of feedback`;
}

/** Heph's offer under a piece of feedback. */
export const FEEDBACK_OFFER =
	"Want to talk this through? I can help you work out what to try next.";

/** Heph introducing itself, before there is any conversation to go back to. */
export const INTRODUCTION =
	"I’m Heph, an AI mentor. We can talk through a piece of feedback, a review comment, or anything else about your work.";
