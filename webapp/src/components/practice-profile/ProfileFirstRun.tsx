import { ArrowRightIcon, CircleSlashIcon, MessageSquareTextIcon, SparklesIcon } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import type { MemberReviewReadiness, ProfileReviewRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { reviewEventPhrases } from "@/components/practice-vocabulary/review-event-defs";
import { OWN_REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { itemName } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";
import { useWorkProvider } from "@/lib/work-provider-context";

import { ALL_PRACTICE_GROUPS_LEVEL } from "./practice-profile-search";

type Blocker = NonNullable<MemberReviewReadiness["blocker"]>;

export interface ProfileFirstRunProps {
	workspaceName: string;
	/** The server's word on whether a review can run on the reader's work, and what starts one. */
	readiness: MemberReviewReadiness;
	/** A review of the reader's work that is waiting or running and has recorded nothing yet. */
	activeReview?: ProfileReviewRef;
	/** Whether any review has recorded something about the reader's work. */
	reviewed: boolean;
	/** The way to the reader's AI choice; absent where they cannot change it, such as in a user view. */
	aiChoiceLink?: (label: string) => ReactElement;
	/** The way to ask for a review of the reader's work; absent where they cannot ask, such as in a user view. */
	requestReviewLink?: ReactElement;
	/**
	 * The ways to the admin pages that remove a blocker; present only for a reader who administers the
	 * workspace, so the member page itself makes no admin read.
	 */
	adminLinks?: Record<AdminFix, (label: string) => ReactElement>;
}

/** The admin page that removes a blocker: the setup summary, or the review scope it does not show. */
type AdminFix = "setupSummary" | "reviewScope";

interface BlockerCopy {
	title: string;
	description: (workspaceName: string) => string;
	/** The label of the way to the AI choice, for a reason the reader can change there. */
	aiChoice?: string;
	/** Where a workspace admin removes this reason, and the link's label in "You can change this in …". */
	adminFix?: { page: AdminFix; label: string };
	/** What a reader who cannot remove the reason does instead; an admin gets the link in its place. */
	askAdmin?: string;
}

/** Why no review runs on the reader's work, in the order the server picks the first that applies. */
const BLOCKERS: Record<Exclude<Blocker, "PERSON_ERASED">, BlockerCopy> = {
	WORKSPACE_INACTIVE: {
		title: "Reviews are paused in this workspace",
		description: (name) =>
			`${name} is inactive, so no review runs on your work. A workspace admin can make it active again.`,
	},
	REVIEWS_OFF: {
		title: "Practice reviews are off here",
		description: (name) =>
			`${name} has practice reviews turned off, so no review runs on your work.`,
		adminFix: { page: "setupSummary", label: "setup summary" },
		askAdmin: "Ask a workspace admin if you expected them.",
	},
	ON_REQUEST_ONLY: {
		title: "Reviews here start only on request",
		description: (name) =>
			`${name} does not review work by itself. You or a workspace admin can ask for a review of eligible work.`,
		adminFix: { page: "setupSummary", label: "setup summary" },
	},
	OUTSIDE_REVIEW_SCOPE: {
		title: "Your work is outside the review scope",
		description: (name) =>
			`A workspace admin chose whose work ${name} reviews. Yours is not part of it yet.`,
		adminFix: { page: "reviewScope", label: "review scope" },
	},
	NOT_ELIGIBLE: {
		title: "Hephaestus cannot match your work to you here yet",
		description: (name) =>
			`${name} reviews only work that it can match to a member. Connect the account this workspace uses, or ask a workspace admin.`,
	},
	NO_AI: {
		title: "Hephaestus does not review your work here",
		description: () =>
			"You chose No AI, so no practice review runs on your work. Feedback you already have stays here.",
		aiChoice: "Change your AI choice",
	},
	CHOICE_REQUIRED: {
		title: "Choose your AI to get reviews",
		description: (name) =>
			`${name} reviews your work only after you choose which AI may handle it.`,
		aiChoice: "Choose your AI",
	},
	NO_MODEL: {
		title: "Practice reviews are not set up for your AI choice yet",
		description: (name) =>
			`${name} has no review model that fits your choice. You get no reviews here until a workspace admin adds one. Your choice still counts.`,
		aiChoice: "Your AI choice",
		adminFix: { page: "setupSummary", label: "setup summary" },
	},
	NO_PRACTICE: {
		title: "No practice here reviews your work yet",
		description: () =>
			"A workspace admin has not chosen a practice that reviews the work of the person who did it.",
		adminFix: { page: "setupSummary", label: "setup summary" },
	},
};

/**
 * Whether the first-run state speaks for an empty feedback list: a reason no review can run always
 * does, and a first review under way and the day-one explanation do until a review records something.
 */
export function firstRunApplies(
	readiness: MemberReviewReadiness,
	reviewed: boolean,
	activeReview?: ProfileReviewRef,
): boolean {
	const blocked = readiness.blocker !== null && readiness.blocker !== "PERSON_ERASED";
	return blocked || (!reviewed && (readiness.blocker === null || activeReview !== undefined));
}

/**
 * What the Practice profile says before it has any feedback, from the reader's real state: why no
 * review can run, a first review that is under way, or what starts one. Nothing once a review has
 * recorded something, where the page's own empty tab speaks. It claims nothing the server did not
 * say, and no time: the reader's own work starts a review.
 */
export function ProfileFirstRun({
	workspaceName,
	readiness,
	activeReview,
	reviewed,
	aiChoiceLink,
	requestReviewLink,
	adminLinks,
}: ProfileFirstRunProps) {
	const provider = useWorkProvider();
	const approval = readiness.approvalBeforePosting
		? `In ${workspaceName}, a workspace admin approves feedback before it is posted on your work.`
		: undefined;

	const { blocker } = readiness;
	// A review under way is real, so it speaks before any reason a future review would not start.
	if (activeReview !== undefined && !reviewed) {
		const status = activeReview.status === "QUEUED" ? "QUEUED" : "RUNNING";
		const { item } = activeReview;
		return (
			<FirstRunEmpty
				icon={<SparklesIcon />}
				title={
					status === "QUEUED"
						? "Your first review is waiting to start"
						: "Your first review is running"
				}
				description={
					<>
						It is on{" "}
						{hasText(item.url) ? (
							<InlineLink href={item.url} external>
								{itemName(item)}
							</InlineLink>
						) : (
							itemName(item)
						)}
						. {OWN_REVIEW_STATUS_DEFS[status].description} Its result appears here when it finishes.
					</>
				}
			>
				{approval !== undefined && <p>{approval}</p>}
			</FirstRunEmpty>
		);
	}

	if (blocker !== null && blocker !== "PERSON_ERASED") {
		const copy = BLOCKERS[blocker];
		const adminLink =
			copy.adminFix === undefined
				? undefined
				: adminLinks?.[copy.adminFix.page](copy.adminFix.label);
		return (
			<FirstRunEmpty
				icon={<CircleSlashIcon />}
				title={copy.title}
				description={
					adminLink === undefined && copy.askAdmin !== undefined
						? `${copy.description(workspaceName)} ${copy.askAdmin}`
						: copy.description(workspaceName)
				}
				action={blockerAction(blocker, copy, { aiChoiceLink, requestReviewLink })}
			>
				{/* A review that someone asks for here can still end in feedback that waits for approval. */}
				{blocker === "ON_REQUEST_ONLY" && approval !== undefined && <p>{approval}</p>}
				{adminLink !== undefined && <p>You can change this in the {adminLink}.</p>}
			</FirstRunEmpty>
		);
	}

	if (!reviewed && blocker === null) {
		const phrases = reviewEventPhrases(readiness.events, provider);
		return (
			<FirstRunEmpty
				icon={<MessageSquareTextIcon />}
				title="No review of your work yet"
				description={`Hephaestus reviews your work against the practices that ${workspaceName} chose.`}
				action={
					<InlineLink
						render={<DetailStackLink entry={ALL_PRACTICE_GROUPS_LEVEL} />}
						className="inline-flex items-center gap-1.5 text-sm font-medium"
					>
						See what this workspace reviews
						<ArrowRightIcon className="size-3.5" aria-hidden />
					</InlineLink>
				}
			>
				{phrases.length > 0 && (
					<div className="text-left">
						<p>A review of your work can start when:</p>
						<ul className="mt-1 list-disc space-y-0.5 pl-5">
							{phrases.map((phrase) => (
								<li key={phrase}>{phrase}</li>
							))}
						</ul>
					</div>
				)}
				<p>
					Whether it runs also depends on the repository and on each practice. Its result appears
					beside the title. When reviews find the same problem in two pieces of your work, a
					feedback card appears here.
				</p>
				{approval !== undefined && <p>{approval}</p>}
			</FirstRunEmpty>
		);
	}

	return null;
}

/** The way out of a blocker that the reader can act on: asking for a review, or their AI choice. */
function blockerAction(
	blocker: Blocker,
	copy: BlockerCopy,
	links: Pick<ProfileFirstRunProps, "aiChoiceLink" | "requestReviewLink">,
): ReactElement | undefined {
	if (blocker === "ON_REQUEST_ONLY") {
		return links.requestReviewLink;
	}
	if (copy.aiChoice === undefined || links.aiChoiceLink === undefined) {
		return undefined;
	}
	return links.aiChoiceLink(copy.aiChoice);
}

function FirstRunEmpty({
	icon,
	title,
	description,
	action,
	children,
}: {
	icon: ReactElement;
	title: string;
	description: ReactNode;
	action?: ReactElement;
	children?: ReactNode;
}) {
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia variant="icon">{icon}</EmptyMedia>
				<EmptyTitle>{title}</EmptyTitle>
				<EmptyDescription>{description}</EmptyDescription>
			</EmptyHeader>
			{(children !== undefined || action !== undefined) && (
				<EmptyContent>
					{children !== undefined && (
						<div className="flex flex-col gap-2 text-sm text-muted-foreground">{children}</div>
					)}
					{action}
				</EmptyContent>
			)}
		</Empty>
	);
}
