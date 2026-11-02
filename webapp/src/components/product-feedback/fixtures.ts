import type { MyProductFeedback } from "@/api/types.gen";
import { daysBefore, hoursBefore, minutesAfter, minutesBefore } from "@/stories/story-clock";

const base = {
	allowPublic: false,
	canKeepPrivate: false,
	canEdit: false,
	canMakePrivate: false,
	canAllowPublic: false,
} satisfies Partial<MyProductFeedback>;

/** A public report in its hold, drafted, with both reverses open. */
export const heldReport = {
	...base,
	id: "b1b1b1b1-0000-0000-0000-000000000001",
	kind: "BUG",
	excerpt: "The practice page jumps to the top every time I change the filter.",
	createdAt: minutesBefore(1),
	state: "HELD",
	route: "PUBLIC",
	filedAfter: minutesAfter(4),
	draftTitle: "Practice page scrolls to the top when the filter changes",
	draftDescription:
		"On a practice page, changing the team filter resets the scroll position to the top of the page.",
	removed: "1 email address",
	allowPublic: true,
	canKeepPrivate: true,
	canEdit: true,
} satisfies MyProductFeedback;

/** A public report while the model drafts it. */
export const draftingReport = {
	...base,
	id: "b1b1b1b1-0000-0000-0000-000000000002",
	kind: "IDEA",
	excerpt: "Let me pin the practices I am working on to the top of my page.",
	createdAt: minutesBefore(0),
	state: "DRAFTING",
	route: "PUBLIC",
	allowPublic: true,
	canKeepPrivate: true,
} satisfies MyProductFeedback;

export const filingReport = {
	...base,
	id: "b1b1b1b1-0000-0000-0000-000000000003",
	kind: "IDEA",
	excerpt: "Show the weekly digest in the app too.",
	createdAt: minutesBefore(6),
	state: "FILING",
	route: "PUBLIC",
	allowPublic: true,
} satisfies MyProductFeedback;

export const publicIssue = {
	...base,
	id: "b1b1b1b1-0000-0000-0000-000000000004",
	kind: "IDEA",
	excerpt: "A dark mode for the practice profile.",
	createdAt: hoursBefore(3),
	state: "FILED",
	route: "PUBLIC",
	issueUrl: "https://github.com/hephaestus-build/Hephaestus/issues/1234",
	issueNumber: 1234,
	draftTitle: "Dark mode for the practice profile",
	allowPublic: true,
	canMakePrivate: true,
} satisfies MyProductFeedback;

export const closedPublicIssue = {
	...publicIssue,
	id: "b1b1b1b1-0000-0000-0000-000000000005",
	excerpt: "Sort practices by name.",
	createdAt: daysBefore(9),
	issueUrl: "https://github.com/hephaestus-build/Hephaestus/issues/1102",
	issueNumber: 1102,
	issueClosedAs: "completed",
} satisfies MyProductFeedback;

/** Kept private by the person; the maintainers can publish it once the person allows it. */
export const privateIssue = {
	...base,
	id: "b1b1b1b1-0000-0000-0000-000000000006",
	kind: "FEEDBACK",
	excerpt: "The review summary is helpful, but it repeats itself on long pull requests.",
	createdAt: daysBefore(1),
	state: "FILED",
	route: "PRIVATE",
	reason: "opted out",
	canAllowPublic: true,
} satisfies MyProductFeedback;

/** Kept private by a check, with no way to allow public. */
export const securityReport = {
	...base,
	id: "b1b1b1b1-0000-0000-0000-000000000007",
	kind: "BUG",
	excerpt: "I can open another workspace’s settings page by changing the address.",
	createdAt: daysBefore(2),
	state: "FILED",
	route: "PRIVATE",
	reason: "security",
} satisfies MyProductFeedback;

export const emailedReport = {
	...base,
	id: "b1b1b1b1-0000-0000-0000-000000000008",
	kind: "FEEDBACK",
	excerpt: "Thanks for the new onboarding.",
	createdAt: daysBefore(20),
	state: "SENT_TO_ADMINS",
	route: "EMAIL",
} satisfies MyProductFeedback;

export const stoppedReport = {
	...base,
	id: "b1b1b1b1-0000-0000-0000-000000000009",
	kind: "BUG",
	excerpt: "Sign-in with GitLab loops back to the login page.",
	createdAt: daysBefore(3),
	state: "STOPPED",
	route: "PRIVATE",
	reason: "GitHub refused it",
} satisfies MyProductFeedback;

export const myReports: MyProductFeedback[] = [
	heldReport,
	draftingReport,
	filingReport,
	publicIssue,
	privateIssue,
	securityReport,
	stoppedReport,
	closedPublicIssue,
	emailedReport,
];
