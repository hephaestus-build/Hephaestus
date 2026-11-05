import type { FeedbackRequest } from "@/api/types.gen";

export type FeedbackKind = FeedbackRequest["kind"];

/** Who a submission reaches, in the product's words; one home so every screen says the same. */
export const READERS = "your instance admins";

interface KindCopy {
	title: string;
	/** The dialog heading once this kind is chosen. */
	heading: string;
	label: string;
	placeholder: string;
}

export const FEEDBACK_KIND_COPY: Record<FeedbackKind, KindCopy> = {
	IDEA: {
		title: "Idea",
		heading: "Share an idea",
		label: "Your idea",
		placeholder: "What would you change or add, and what would it let you do?",
	},
	BUG: {
		title: "Bug",
		heading: "Report a bug",
		label: "What happened?",
		placeholder: "What were you doing, what happened, and what did you expect instead?",
	},
	FEEDBACK: {
		title: "General",
		heading: "Send feedback",
		label: "Your feedback",
		placeholder: "What happened, or what would you like to improve?",
	},
};
