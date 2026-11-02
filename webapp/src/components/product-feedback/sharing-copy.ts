import type { MyProductFeedback } from "@/api/types.gen";

/**
 * The approved wording for the choice about public GitHub issues (lane PF legal draft, part C).
 * The operator's privacy notice names the model provider, so the screens say "An AI model".
 */
export const SHARING_COPY = {
	question: "Can this become a public GitHub issue?",
	share: {
		title: "Yes, share it publicly.",
		detail:
			"Hephaestus removes personal details it finds, but it can miss some. Anyone can read a public issue, and copies can remain after you remove it.",
	},
	keepPrivate: {
		title: "No, keep it private.",
		detail: "The maintainers see it in a private GitHub repository.",
	},
	footnote:
		"An AI model drafts the issue either way. We remember your choice, and you can change it for each report.",
	switchLabel: "Public GitHub issue",
	switchDetail: "Personal details removed",
	changeDefault: "Change default",
	makePrivate:
		"Move this issue to the private repository? It disappears from public view. Copies made while it was public can remain.",
} as const;

/**
 * The server sends why a report is not public as a few words (`FilingReason.words`). Each known
 * reason gets a sentence the person can read; an unknown one is still shown, never dropped.
 */
const REASON_SENTENCES: Record<string, string> = {
	"opted out": "You chose to keep it private.",
	security: "It may be about a security problem, so it stays private.",
	redaction: "Hephaestus removed details from it, so it stays private.",
	"about a person": "It is about a person, so it stays private.",
	"low confidence": "A check was not sure it is safe to make public, so it stays private.",
	abuse: "A check flagged its wording, so it stays private.",
	"no draft": "No issue could be drafted from it, so it stays private.",
	"destination changed":
		"Where reports go changed while it waited, so the admins of this instance have it.",
	"destination unavailable":
		"We could not reach the repository, so the admins of this instance have it and can try again.",
	"GitHub refused it":
		"GitHub refused it, so the admins of this instance have it and can try again.",
};

export function reasonSentence(reason: string): string {
	return REASON_SENTENCES[reason] ?? `It stays private: ${reason}.`;
}

/** What redaction took out, as a line the person can check. */
export function removedLine(removed: string): string {
	return `Removed: ${removed}`;
}

/** A change the person makes to one of their reports; each control waits out the one in flight. */
export type ProductFeedbackAction = "keep-private" | "edit" | "make-private" | "allow-public";

/** States in which the report can still change on its own, so a confirmation keeps reading it. */
export function isSettling(state: MyProductFeedback["state"]): boolean {
	return state === "DRAFTING" || state === "HELD" || state === "FILING";
}

/** "in about 4 minutes", counted down from the time the hold ends. */
export function holdCountdown(filedAfter: Date, now: number): string {
	const minutes = Math.ceil((filedAfter.getTime() - now) / 60_000);
	if (minutes <= 0) {
		return "in less than a minute";
	}
	return minutes === 1 ? "in about 1 minute" : `in about ${minutes} minutes`;
}
