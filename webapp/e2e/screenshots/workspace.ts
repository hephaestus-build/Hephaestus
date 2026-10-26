/**
 * The synthetic workspace that the user-guide screenshots show. Every person, repository and piece of
 * work here is invented; the practice and group names come from the shipped catalog, so the screens
 * show what a new workspace adopts.
 */
import { HttpResponse, http } from "msw";

import type {
	AgentBinding,
	ConsentStatus,
	CurrentUserView,
	FeatureFlags,
	IntegrationCatalogEntry,
	ItemRef,
	MentorConversation,
	MentorEntry,
	MentorEntryPage,
	PracticeGroupStanding,
	PracticePageFeedback,
	PracticeProfileOverview,
	PracticeStanding,
	PageResponseDtoReviewFeedback,
	PracticeStandingResult,
	ReviewedPractice,
	Workspace,
	WorkspaceListItem,
	WorkspaceMembership,
	WorkspaceOnboarding,
} from "@/api/types.gen";
import { buildFeedbackModeFixture } from "@/components/admin/practices/practice-feedback-mode/fixtures";
import type { Wire } from "@/lib/dates";

/** Every screen is captured at this moment, so relative times read the same in every run. */
export const NOW = new Date("2026-10-20T09:30:00Z");
const daysAgo = (days: number) => new Date(NOW.getTime() - days * 86_400_000).toISOString();

export const WORKSPACE = "orbit";
const REPOSITORY = "orbit-labs/telemetry-service";

const reader: CurrentUserView = {
	id: 7,
	username: "alex-rivera",
	displayName: "Alex Rivera",
	primaryEmail: "alex@example.com",
	profileUrl: "https://github.com/alex-rivera",
	appRole: "USER",
	roles: ["ROLE_USER"],
	status: "ACTIVE",
	identityProvider: "GITHUB",
	gitProviderId: "1001",
	linkedProviders: [{ type: "GITHUB", serverUrl: "https://github.com" }],
};

function pullRequest(number: number, title: string): Wire<ItemRef> {
	return {
		container: REPOSITORY,
		id: `pr-${number}`,
		kind: "scm.pull_request",
		label: `#${number}`,
		parentId: null,
		path: null,
		provider: "GITHUB",
		removedAt: null,
		title,
		url: `https://github.com/${REPOSITORY}/pull/${number}`,
	};
}

const retries = pullRequest(214, "Retry the ingest client with backoff");
const paging = pullRequest(219, "Page the dashboard query");
const metrics = pullRequest(223, "Export queue depth as a metric");

function result(
	item: Wire<ItemRef>,
	outcome: PracticeStandingResult["outcome"],
	title: string,
	deliveredFeedback: string | null = null,
): Wire<PracticeStandingResult> {
	return {
		deliveredFeedback,
		item,
		origin: "EVENT",
		outcome,
		resultId: `${item.id}-${title}`,
		severity: outcome === "NOT_MET" ? "MINOR" : null,
		title,
	};
}

interface Group {
	slug: string;
	name: string;
	icon: string;
	color: string;
	standing: PracticeGroupStanding["standing"];
	practices: {
		slug: string;
		name: string;
		standing: PracticeStanding["standing"];
		results: Wire<PracticeStandingResult>[];
	}[];
}

const groups: Group[] = [
	{
		slug: "review-ready-work",
		name: "Packaging work for review",
		icon: "Package",
		color: "sky",
		standing: "MIXED",
		practices: [
			{
				slug: "describe-what-and-why",
				standing: "STRENGTH",
				name: "Describe what changed and why",
				results: [
					result(retries, "MET", "The description names the failure that the retries fix."),
					result(paging, "MET", "The description explains why the query needs pages."),
				],
			},
			{
				slug: "states-how-to-verify-the-change",
				standing: "DEVELOPING",
				name: "State how to verify the change",
				results: [
					result(
						metrics,
						"NOT_MET",
						"The description does not say how to see the new metric.",
						"Add the query that shows the queue depth, so a reviewer can check the metric.",
					),
				],
			},
		],
	},
	{
		slug: "acting-on-review-feedback",
		name: "Acting on review feedback",
		icon: "MessageSquareReply",
		color: "cyan",
		standing: "STRENGTH",
		practices: [
			{
				slug: "engaging-with-inline-review-comments",
				standing: "STRENGTH",
				name: "Respond to each review comment",
				results: [
					result(retries, "MET", "Each review comment has a reply or a follow-up commit."),
					result(paging, "MET", "The open question about page size has an answer."),
				],
			},
		],
	},
	{
		slug: "testing-discipline",
		name: "Testing your changes",
		icon: "TestTube",
		color: "amber",
		standing: "DEVELOPING",
		practices: [
			{
				slug: "ships-tests-with-the-change",
				standing: "MIXED",
				name: "Include tests with the change",
				results: [
					result(
						retries,
						"NOT_MET",
						"The retry delay has no test.",
						"Add a test that checks the delay doubles after each failed attempt.",
					),
					result(paging, "MET", "A test covers the last, partial page."),
				],
			},
		],
	},
];

const practiceStandings: Wire<PracticeStanding>[] = groups.flatMap((group) =>
	group.practices.map((practice) => ({
		basis: "CURRENT_REVIEWS",
		direction: null,
		doesNotApplyOnly: false,
		groupName: group.name,
		groupSlug: group.slug,
		name: practice.name,
		slug: practice.slug,
		standing: practice.standing,
		strengths: practice.results.filter((observed) => observed.outcome === "MET"),
		toWorkOn: practice.results.filter((observed) => observed.outcome === "NOT_MET"),
		trendSupport: null,
		whatGoodLooksLike: null,
		whyItMatters: null,
	})),
);

const groupStandings: Wire<PracticeGroupStanding>[] = groups.map((group) => ({
	basis: "CURRENT_REVIEWS",
	direction: null,
	feedbackSince: daysAgo(21),
	feedbackSpanDays: 21,
	groupColor: group.color,
	groupDescription: null,
	groupIcon: group.icon,
	groupName: group.name,
	groupSlug: group.slug,
	guidance: null,
	guidanceSource: null,
	results: group.practices.flatMap((practice) => practice.results),
	sources: [{ count: 3, itemKind: "scm.pull_request" }],
	standing: group.standing,
	trendSupport: null,
}));

const reviewedPractices: Wire<ReviewedPractice>[] = groups.flatMap((group) =>
	group.practices.map((practice) => ({
		group: { color: group.color, icon: group.icon, name: group.name, slug: group.slug },
		name: practice.name,
		slug: practice.slug,
		whatGoodLooksLike: null,
		whyItMatters: null,
	})),
);

const overview: Wire<PracticeProfileOverview> = {
	changes: [
		{
			at: daysAgo(1),
			cleanNeeded: null,
			direction: null,
			evidence: [metrics],
			feedbackId: "feedback-metrics",
			from: null,
			groupName: "Packaging work for review",
			groupSlug: "review-ready-work",
			practiceName: "State how to verify the change",
			practiceSlug: "states-how-to-verify-the-change",
			resolvedBy: null,
			to: null,
			type: "FEEDBACK_NEW",
		},
	],
	latestReview: { at: daysAgo(1), item: metrics, reviewId: "review-223", status: "COMPLETE" },
	reviewedItems: [retries, paging, metrics],
	window: { since: daysAgo(28), until: NOW.toISOString() },
};

function feedback(
	id: string,
	practice: { slug: string; name: string; group: Group },
	item: Wire<ItemRef>,
	text: { headline: string; body: string; nextStep: string; summary: string },
	preparedDaysAgo: number,
): Wire<PracticePageFeedback> {
	return {
		body: text.body,
		cleanNeeded: 2,
		cleanWork: [],
		closedAt: null,
		closedBy: null,
		evidence: [
			{
				item,
				recordedAt: daysAgo(preparedDaysAgo),
				resultId: `${id}-result`,
				reviewId: `${id}-review`,
				summary: text.summary,
			},
		],
		groupName: practice.group.name,
		groupSlug: practice.group.slug,
		headline: text.headline,
		id,
		nextStep: text.nextStep,
		practiceName: practice.name,
		practiceSlug: practice.slug,
		preparedAt: daysAgo(preparedDaysAgo),
		readAt: null,
		response: null,
		unverifiedHistory: false,
		whatGoodLooksLike: null,
		whyItMatters: null,
		withdrawn: false,
	};
}

const [packaging, , testing] = groups;
const practiceFeedback: Wire<PracticePageFeedback>[] =
	packaging === undefined || testing === undefined
		? []
		: [
				feedback(
					"feedback-metrics",
					{
						slug: "states-how-to-verify-the-change",
						name: "State how to verify the change",
						group: packaging,
					},
					metrics,
					{
						headline: "Reviewers cannot see how to check your change",
						body: "Your last two pull requests describe the change well, but neither says how a reviewer can see that it works.",
						nextStep:
							"Add a short “How to verify” section with the command or the screen that shows the result.",
						summary: "The description does not say how to see the new metric.",
					},
					1,
				),
				feedback(
					"feedback-retries",
					{
						slug: "ships-tests-with-the-change",
						name: "Include tests with the change",
						group: testing,
					},
					retries,
					{
						headline: "New behavior arrives without a test",
						body: "The retry delay in #214 changes how the client behaves under load, and no test pins it down.",
						nextStep: "Add a test that checks the delay doubles after each failed attempt.",
						summary: "The retry delay has no test.",
					},
					6,
				),
			];

function entry(
	seq: number,
	kind: MentorEntry["kind"],
	text: string,
	minutesAgo: number,
): Wire<MentorEntry> {
	return {
		conversationId: "conversation-alex",
		createdAt: new Date(NOW.getTime() - minutesAgo * 60_000).toISOString(),
		deleted: false,
		id: `entry-${seq}`,
		kind,
		links: [],
		seq,
		surface: "APP",
		text,
		...(kind === "HEPH" ? { turnStatus: "DONE" as const } : {}),
	};
}

const entries: Wire<MentorEntryPage> = {
	entries: [
		entry(1, "USER", "Why did I get feedback about how to verify #223?", 6),
		entry(
			2,
			"HEPH",
			[
				"The description of #223 says what the new queue-depth metric is, but not how to see it.",
				"A reviewer would have to find the dashboard or the query on their own.",
				"",
				"Your earlier pull requests had the same gap, so I raised it as a pattern, not as a one-off.",
				"",
				"Next time, add a short **How to verify** section, for example:",
				"",
				"- Run the ingest load test.",
				"- Open the *Queue* dashboard and check that `queue_depth` rises and falls.",
			].join("\n"),
			5,
		),
		entry(3, "USER", "Does a screenshot of the dashboard count?", 2),
		entry(
			4,
			"HEPH",
			"Yes. A screenshot shows the result, and a sentence that says where to find it lets the reviewer check it themselves.",
			1,
		),
	],
};

const conversation: Wire<MentorConversation> = {
	activeTurns: [],
	id: "conversation-alex",
	lastAnswerAt: new Date(NOW.getTime() - 60_000).toISOString(),
	lastReadAt: new Date(NOW.getTime() - 60_000).toISOString(),
	latestSeq: 4,
	threads: [],
};

const workspace: Wire<WorkspaceListItem> = {
	accountLogin: "orbit-labs",
	createdAt: daysAgo(120),
	displayName: "Orbit Labs",
	id: 1,
	practicesEnabled: true,
	providerType: "GITHUB",
	publishesPublicActivity: false,
	status: "ACTIVE",
	workspaceAddress: `https://hephaestus.example/w/${WORKSPACE}`,
	workspaceSlug: WORKSPACE,
};

const membership = (role: WorkspaceMembership["role"]): Wire<WorkspaceMembership> => ({
	account: {
		id: "7",
		status: "ACTIVE",
		displayName: "Alex Rivera",
		avatarUrl: null,
		identities: [
			{ provider: "GITHUB", login: "alex-rivera", displayName: "Alex Rivera", avatarUrl: null },
		],
	},
	role,
	joinedAt: daysAgo(120),
	leftAt: null,
	hidden: false,
	reviewSelected: true,
	eligibleForPracticeReview: true,
});

const onboarding: WorkspaceOnboarding = {
	aiChoice: "IN_HOUSE_ONLY",
	aiChoiceRequired: false,
	aiOptions: [],
	enabled: true,
	links: [],
	needsSetup: false,
	workspaceName: "Orbit Labs",
};

const consent: ConsentStatus = {
	completed: true,
	noticeVersion: "2026-10-04",
	participateInResearch: false,
};
const features: FeatureFlags = {};

const workspaceDetail: Wire<Workspace> = {
	accountLogin: "orbit-labs",
	createdAt: daysAgo(120),
	displayName: "Orbit Labs",
	gitlabWebhookRegistered: false,
	hasPersonalAccessToken: false,
	hasSlackToken: false,
	id: 1,
	installationId: 4242,
	practiceReviewAutoTriggerEnabled: true,
	practiceReviewManualTriggerEnabled: true,
	practicesEnabled: true,
	providerType: "GITHUB",
	publicActivityEnabled: false,
	status: "ACTIVE",
	updatedAt: daysAgo(2),
	workspaceAddress: `https://hephaestus.example/w/${WORKSPACE}`,
	workspaceSlug: WORKSPACE,
};

const integrations: IntegrationCatalogEntry[] = [
	{
		connected: true,
		connectionId: 1,
		connectionState: "ACTIVE",
		displayName: "GitHub",
		kind: "GITHUB",
	},
	{ connected: false, displayName: "Slack", kind: "SLACK" },
	{ connected: false, displayName: "Outline", kind: "OUTLINE" },
];

const agents: AgentBinding[] = [
	{
		dataHandlingTier: "IN_HOUSE",
		enabled: true,
		purpose: "PRACTICE_REVIEW",
		ready: true,
		servedTiers: ["IN_HOUSE"],
		workspaceModelId: 1,
	},
	{
		dataHandlingTier: "IN_HOUSE",
		enabled: true,
		purpose: "MENTOR",
		ready: true,
		servedTiers: ["IN_HOUSE"],
		workspaceModelId: 1,
	},
];

/** The workspace keeps Approve first; one group sends its feedback without approval. */
const feedbackModes = buildFeedbackModeFixture({
	groups: groups.map((group) => ({
		slug: group.slug,
		name: group.name,
		...(group.slug === "acting-on-review-feedback" ? { override: "AUTOMATIC" as const } : {}),
		practices: group.practices.map((practice) => ({ name: practice.name })),
	})),
});

const noReviewFeedback: Wire<PageResponseDtoReviewFeedback> = {
	content: [],
	empty: true,
	first: true,
	last: true,
	number: 0,
	numberOfElements: 0,
	pageable: {
		offset: 0,
		pageNumber: 0,
		pageSize: 20,
		paged: true,
		unpaged: false,
		sort: { empty: true, sorted: false, unsorted: true },
	},
	size: 20,
	sort: { empty: true, sorted: false, unsorted: true },
	totalElements: 0,
	totalPages: 0,
};

const ws = `*/workspaces/${WORKSPACE}`;

/** The API answers that the user-guide screens need, for a reader with this workspace role. */
export const handlers = (role: WorkspaceMembership["role"]) => [
	http.get("*/user", () => HttpResponse.json(reader)),
	http.get("*/user/consent", () => HttpResponse.json(consent)),
	http.get("*/user/features", () => HttpResponse.json(features)),
	http.get("*/workspaces", () => HttpResponse.json([workspace])),
	http.get(ws, () => HttpResponse.json(workspaceDetail)),
	http.get(`${ws}/connections/catalog`, () => HttpResponse.json(integrations)),
	http.get(`${ws}/agents`, () => HttpResponse.json(agents)),
	http.get(`${ws}/practices`, () => HttpResponse.json(feedbackModes.practices)),
	http.get(`${ws}/practice-groups`, () => HttpResponse.json(feedbackModes.groups)),
	http.get(`${ws}/practices/review-settings`, () => HttpResponse.json(feedbackModes.settings)),
	http.get(`${ws}/practices/feedback-modes`, () => HttpResponse.json(feedbackModes.rollup)),
	http.get(`${ws}/practices/reviews/feedback`, () => HttpResponse.json(noReviewFeedback)),
	http.get(`${ws}/members/me`, () => HttpResponse.json(membership(role))),
	http.get(`${ws}/onboarding/me`, () => HttpResponse.json(onboarding)),
	http.get(`${ws}/product-feedback/surveys`, () => HttpResponse.json([])),
	http.get(`${ws}/practices/reviewed`, () => HttpResponse.json(reviewedPractices)),
	http.get(`${ws}/practices/standings`, () => HttpResponse.json(practiceStandings)),
	http.get(`${ws}/practice-groups/standings`, () => HttpResponse.json(groupStandings)),
	http.get(`${ws}/practice-profile/overview`, () => HttpResponse.json(overview)),
	http.get(`${ws}/practices/feedback/practice-page`, () => HttpResponse.json(practiceFeedback)),
	http.get(`${ws}/mentor/conversation`, () => HttpResponse.json(conversation)),
	http.get(`${ws}/mentor/entries`, () => HttpResponse.json(entries)),
	// No live updates: each screen shows one moment.
	http.get(`${ws}/mentor/events`, () => new HttpResponse(null, { status: 204 })),
];
