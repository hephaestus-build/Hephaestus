import type { FeedbackRequest } from "@/api/types.gen";

export type FeedbackKind = FeedbackRequest["kind"];

/** Who a submission reaches, in the product's words; one home so every screen says the same. */
export const READERS = "the Hephaestus team";

interface KindCopy {
	title: string;
	detail: string;
	/** The menu entry, and the dialog's heading once this kind is chosen. */
	heading: string;
	label: string;
	placeholder: string;
}

export const FEEDBACK_KIND_COPY: Record<FeedbackKind, KindCopy> = {
	IDEA: {
		title: "Idea",
		detail: "A feature or change that would help.",
		heading: "Share an idea",
		label: "Your idea",
		placeholder: "What would you change or add, and what would it let you do?",
	},
	BUG: {
		title: "Bug",
		detail: "Something broke or behaved unexpectedly.",
		heading: "Report a bug",
		label: "What happened?",
		placeholder: "What were you doing, what happened, and what did you expect instead?",
	},
	FEEDBACK: {
		title: "Feedback",
		detail: "What works, what gets in your way.",
		heading: "Send feedback",
		label: "Your feedback",
		placeholder: "What works well for you, and what does not?",
	},
};
