/**
 * The demo the dev seed writes for Practices across the workspace and the Practice profile: who the
 * synthetic developers are, how each practice group splits them, and the reader's own reviews and
 * in-app feedback. Pure data and pure functions, so a test can check the demo's shape without a
 * database; `scripts/seed-practices-across-the-workspace.ts` writes it.
 */

/** How many synthetic developers join the workspace. */
export const DEVELOPERS = 40;
/** Logins no provider hands out to a person, so a synthetic developer is never mistaken for one. */
export const LOGIN_PREFIX = "synthetic-developer-";
/** Provider ids far above any real account's, so the seed never collides with a synced user. */
export const NATIVE_ID_BASE = 990_000_000;
/** A UUID v4 prefix no real row carries; the fourth group says which table the row is in. */
export const ID_PREFIX = "5eed0000-ac05-4000";
export const TABLE = {
	job: "8000",
	observation: "8001",
	feedback: "8002",
	reaction: "8003",
} as const;

export function seedId(table: (typeof TABLE)[keyof typeof TABLE], ordinal: number): string {
	return `${ID_PREFIX}-${table}-${ordinal.toString(16).padStart(12, "0")}`;
}

/** The seed's first job, which keeps the ids of the revisions the server appended for the seed. */
export const FIRST_JOB = seedId(TABLE.job, 1);

/**
 * The UTC calendar day `days` before today, at `time` ("HH:MM") UTC. Every moment in the seed is one
 * of these, so a run stays inside the standing's 90-day look-back and a closed card inside the page's
 * 30 days however long after this file was written the seed runs.
 */
export function daysAgo(days: number, time: string): string {
	const day = new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);
	return new Date(`${day}T${time}:00Z`).toISOString();
}

// --- the synthetic developers ---------------------------------------------------------------------

export type Bucket = "needs" | "mixed" | "well" | "none";

/**
 * How the 40 developers split in each group: Needs attention, Mixed feedback, Going well, none.
 * A group not listed here has no practice a pull request or issue review can observe, so nobody
 * gets a standing in it and the page withholds its split.
 */
export const SPLITS: Record<string, [number, number, number, number]> = {
	"acting-on-review-feedback": [9, 9, 9, 13],
	"delivery-and-version-control-discipline": [9, 10, 9, 12],
	"robust-error-handling": [6, 10, 10, 14],
	"secure-by-default-changes": [10, 9, 9, 12],
	"review-ready-work": [9, 9, 10, 12],
	"decisions-and-documentation": [9, 9, 9, 13],
	"constructive-code-review": [10, 10, 9, 11],
	// Two at Needs attention, and the reader there too: three, so the split is held back.
	"testing-discipline": [2, 12, 13, 13],
	// Three with a standing: held back.
	"issue-traceability-and-lifecycle": [1, 1, 1, 37],
	"actionable-issue-authoring": [9, 9, 10, 12],
	"code-craftsmanship": [10, 9, 9, 12],
};

/** How many developers with a standing in a group leave each of its practices unreviewed. */
export const SKIPPERS_PER_PRACTICE = 6;

/** Which bucket developer `index` falls in for the group at `groupIndex`, shuffled per group. */
export function bucketOf(
	split: [number, number, number, number],
	groupIndex: number,
	index: number,
): Bucket {
	const position = (index * 7 + groupIndex * 5) % DEVELOPERS;
	const [needs, mixed, well] = split;
	if (position < needs) {
		return "needs";
	}
	if (position < needs + mixed) {
		return "mixed";
	}
	return position < needs + mixed + well ? "well" : "none";
}

/**
 * Whether the run `newest` places from the newest is a problem for a developer in `bucket`. Under
 * the standing's recency weights, clean then one slip then clean reads Mixed feedback, and two slips
 * on the newest two pieces of work read Needs attention.
 */
export function isProblem(bucket: Bucket, newest: number): boolean {
	if (bucket === "mixed") {
		return newest === 1;
	}
	if (bucket === "needs") {
		return newest !== 2;
	}
	return false;
}

// --- the reader -----------------------------------------------------------------------------------

export type Outcome = "MET" | "NOT_MET" | "NOT_APPLICABLE" | "UNDETERMINED";
export type Severity = "CRITICAL" | "MAJOR" | "MINOR" | "INFO";

export interface ArtifactRef {
	kind: "scm.pull_request" | "scm.issue";
	/** The number in the seed's pull request or issue repository. */
	number: number;
}

export interface Citation {
	sourceKind: string;
	path: string;
	startLine: number;
	endLine: number;
	quote: string;
	side?: "NEW" | "OLD";
}

export interface SeedObservation {
	practice: string;
	outcome: Outcome;
	/** Required exactly for NOT_MET. */
	severity?: Severity;
	/** The observation's title on the timeline; at most 255 characters. */
	summary: string;
	/** Why it was noted. */
	rationale?: string;
	citation: Citation;
}

export interface SeedRun {
	key: string;
	artifact: ArtifactRef;
	at: string;
	observations: SeedObservation[];
}

export interface SeedResponse {
	at: string;
	usefulness?: "HELPFUL" | "UNHELPFUL";
	resolution?: "ADDRESSED" | "DISPUTED" | "NOT_APPLICABLE";
	/** Required for a dispute. */
	explanation?: string;
}

export interface SeedCard {
	practice: string;
	/** The run whose cycle composed the card; its job owns the feedback. */
	composedBy: string;
	createdAt: string;
	/** Set once the developer opened the card; absent for one still unread. */
	deliveredAt?: string;
	headline: string;
	message: string;
	nextStep: string;
	/** The runs whose problem observation on this practice the card stands on. */
	evidence: string[];
	response?: SeedResponse;
}

const pullRequest = (number: number): ArtifactRef => ({ kind: "scm.pull_request", number });
const issue = (number: number): ArtifactRef => ({ kind: "scm.issue", number });

const diff = (file: string, startLine: number, endLine: number, quote: string): Citation => ({
	sourceKind: "scm.pull-request.diff",
	path: file,
	startLine,
	endLine,
	quote,
	side: "NEW",
});
const description = (quote: string, lines = 1): Citation => ({
	sourceKind: "scm.pull-request.core",
	path: "description",
	startLine: 1,
	endLine: lines,
	quote,
});
const commits = (quote: string, lines: number): Citation => ({
	sourceKind: "scm.pull-request.core",
	path: "commits",
	startLine: 1,
	endLine: lines,
	quote,
});
const files = (quote: string): Citation => ({
	sourceKind: "scm.pull-request.core",
	path: "files",
	startLine: 1,
	endLine: 1,
	quote,
});
const linkedIssues = (quote: string, lines = 1): Citation => ({
	sourceKind: "scm.linked-work-items",
	path: "linked-issues",
	startLine: 1,
	endLine: lines,
	quote,
});
const reviewThreads = (quote: string, lines = 1): Citation => ({
	sourceKind: "scm.review-threads",
	path: "review-threads",
	startLine: 1,
	endLine: lines,
	quote,
});
const issueBody = (quote: string, lines: number): Citation => ({
	sourceKind: "scm.issue.core",
	path: "body",
	startLine: 1,
	endLine: lines,
	quote,
});

const USER_SERVICE = "src/main/java/de/tum/cit/aet/users/UserService.java";
const USER_CONTROLLER = "src/main/java/de/tum/cit/aet/users/UserController.java";
const ROLE_CONTROLLER = "src/main/java/de/tum/cit/aet/users/RoleController.java";

/**
 * The reader's reviews, oldest first. Each run reviews one piece of work on one day and records what
 * the practices found there. The picture they draw over the reader's practice standings: untrusted
 * input improves across eight pull requests, scoping declines across eight, descriptions and review
 * comments recover after two problems, three practices stay mixed or in need of attention, and
 * dependency changes never come up.
 */

export const READER_RUNS: SeedRun[] = [
	{
		key: "pr1",
		artifact: pullRequest(1),
		at: daysAgo(41, "09:30"),
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The notification body interpolates the display name without escaping it.",
				rationale:
					"NotificationService builds the email HTML with string concatenation and inserts the request's display name as it arrived, so a name that carries markup ends up in the mail body.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/notifications/NotificationService.java",
					41,
					43,
					'String body = "<p>Hello " + request.getDisplayName() + ",</p>";',
				),
			},
			{
				practice: "scope-one-reviewable-change",
				outcome: "MET",
				summary: "The change adds the notification service and nothing else.",
				rationale:
					"Every file in the diff serves the new service; the unrelated import fix was left for its own change.",
				citation: description(
					"Adds the email and Slack notification service behind one interface.",
					2,
				),
			},
		],
	},
	{
		key: "pr2",
		artifact: pullRequest(2),
		at: daysAgo(38, "14:10"),
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The user lookup builds its query by concatenating the login.",
				rationale:
					"UserRepository.findByLogin assembles the SQL with string concatenation, so the login is part of the statement rather than a parameter.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/users/UserRepository.java",
					27,
					28,
					'String sql = "SELECT * FROM users WHERE login = \'" + login + "\'";',
				),
			},
			{
				practice: "scope-one-reviewable-change",
				outcome: "MET",
				summary: "One fix, one file, one intention.",
				rationale: "The diff touches the failing lookup and its test and nothing else.",
				citation: description("Fixes the user lookup that failed for logins with a dot."),
			},
			{
				practice: "leaves-useful-specific-review-comments",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "Two review comments say the code looks wrong without saying what to change.",
				rationale:
					'The comments on the lookup read "this looks wrong" and "hmm", and the author asked back on both.',
				citation: reviewThreads("this looks wrong", 2),
			},
		],
	},
	{
		key: "pr4",
		artifact: pullRequest(4),
		at: daysAgo(34, "10:45"),
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "The domain exception carries the raw login into the response body.",
				rationale:
					"UserNotFoundException formats the login into its message, and the exception handler writes that message to the response as it is.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/users/UserNotFoundException.java",
					12,
					13,
					'super("No user with login " + login);',
				),
			},
			{
				practice: "scope-one-reviewable-change",
				outcome: "MET",
				summary: "The refactor stays inside the error handling it names.",
				rationale:
					"Every hunk replaces a return code with a domain exception; no behaviour change rides along.",
				citation: description("Replaces the error codes in UserService with domain exceptions."),
			},
			{
				practice: "leaves-useful-specific-review-comments",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "A comment asks whether the code can be better without saying how.",
				rationale:
					'The comment on the exception mapper reads "can we do better here?" and the author replied asking what better means.',
				citation: reviewThreads("can we do better here?"),
			},
		],
	},
	{
		key: "pr16",
		artifact: pullRequest(16),
		at: daysAgo(32, "16:20"),
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The report runner opens the file named by the request.",
				rationale:
					"ReportRunner resolves the requested file name against the reports directory without normalising it, so a relative path escapes the directory.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/reports/ReportRunner.java",
					58,
					59,
					"Path report = reportsDir.resolve(request.getFileName());",
				),
			},
			{
				practice: "scope-one-reviewable-change",
				outcome: "MET",
				summary: "The runner arrives on its own.",
				citation: description("Adds a report runner that executes one report definition."),
			},
			{
				practice: "describe-what-and-why",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The description lists the files touched and not the problem behind them.",
				rationale:
					"The description names ReportRunner and its test and says nothing about why a runner is needed or what it replaces.",
				citation: description("Adds ReportRunner and ReportRunnerTest.", 2),
			},
			{
				practice: "honours-linked-issue-acceptance-criteria",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "The pull request closes its issue without naming a criterion.",
				rationale:
					"The description closes an issue that lists three acceptance criteria and does not say which of them the runner meets.",
				citation: linkedIssues("Closes the report runner issue."),
			},
		],
	},
	{
		key: "issue13",
		artifact: issue(13),
		at: daysAgo(27, "09:05"),
		observations: [
			{
				practice: "issue-has-checkable-outcome",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "The issue says the test is flaky and not what fixed looks like.",
				rationale:
					"The issue describes the failing integration test and how often it fails, and ends without a condition a maintainer could check before closing it.",
				citation: issueBody("The integration test fails about one run in five on CI.", 6),
			},
			{
				practice: "issue-states-an-actionable-problem",
				outcome: "MET",
				summary: "The issue names the failing test and how often it fails.",
				rationale:
					"The body names the test class, the CI job and the failure rate, which is what a maintainer needs to start.",
				citation: issueBody(
					"UserServiceIntegrationTest.deactivatesDormantAccounts fails about one run in five.",
					3,
				),
			},
		],
	},
	{
		key: "pr17",
		artifact: pullRequest(17),
		at: daysAgo(26, "11:50"),
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				outcome: "MET",
				summary: "The deactivation endpoint validates the id and binds it as a parameter.",
				rationale:
					"The id is parsed as a number before the lookup and the lookup binds it; nothing from the request reaches a sink as text.",
				citation: diff(USER_CONTROLLER, 33, 35, "@PathVariable long userId"),
			},
			{
				practice: "scope-one-reviewable-change",
				outcome: "MET",
				summary: "One endpoint, one intention.",
				citation: description("Adds POST /users/{id}/deactivate."),
			},
			{
				practice: "describe-what-and-why",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The description says what was added and not why.",
				rationale:
					"The description repeats the endpoint's signature; the reviewer's first comment asks what deactivation is for.",
				citation: description("Adds a user deactivation endpoint.", 2),
			},
		],
	},
	{
		key: "issue14",
		artifact: issue(14),
		at: daysAgo(20, "13:25"),
		observations: [
			{
				practice: "issue-has-checkable-outcome",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "The issue asks for backoff without saying how a maintainer would verify it.",
				rationale:
					"The body asks for rate-limit backoff on the GraphQL client and names no limit, no retry budget and no check.",
				citation: issueBody("The GraphQL client should back off when GitHub rate limits us.", 4),
			},
			{
				practice: "issue-states-an-actionable-problem",
				outcome: "MET",
				summary: "The issue states the failure a maintainer can reproduce.",
				citation: issueBody("Sync jobs fail with 403 once the hourly budget is spent.", 2),
			},
		],
	},
	{
		key: "pr19",
		artifact: pullRequest(19),
		at: daysAgo(18, "15:40"),
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				outcome: "MET",
				summary: "The listing filter is parsed into an enum before it reaches the query.",
				citation: diff(
					USER_CONTROLLER,
					52,
					53,
					"UserStatus status = UserStatus.valueOf(filter.toUpperCase(Locale.ROOT));",
				),
			},
			{
				practice: "scope-one-reviewable-change",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The deactivation change carries a rename of the listing beside it.",
				rationale:
					"Sixteen of the twenty-three files in the diff rename ActiveUserList to UserListing; the deactivation change itself is four files.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/users/UserListing.java",
					1,
					3,
					"public class UserListing {",
				),
			},
			{
				practice: "describe-what-and-why",
				outcome: "MET",
				summary: "The description opens with the support request the listing answers.",
				citation: description(
					"Support asked for a way to see who is still active before a deactivation sweep.",
					2,
				),
			},
			{
				practice: "ready-and-traceable-handoff",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "Marked ready with no link to the issue that asked for the listing.",
				rationale:
					"The pull request was marked ready without a linked issue, and the reviewer opened with a question about the listing's purpose.",
				citation: linkedIssues("No linked issues."),
			},
			{
				practice: "commit-subjects-explain-each-change",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "Five of eight commit subjects say fix or wip.",
				rationale:
					'The commits read "wip", "fix", "fix again", "address comments" and "wip"; the history does not say which commit added the listing.',
				citation: commits("wip", 8),
			},
		],
	},
	{
		key: "pr20",
		artifact: pullRequest(20),
		at: daysAgo(9, "10:15"),
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				outcome: "MET",
				summary: "The new role is validated against the enum before it is stored.",
				citation: diff(ROLE_CONTROLLER, 40, 41, "Role role = Role.parse(request.role());"),
			},
			{
				practice: "scope-one-reviewable-change",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The role change ships with a cleanup of the user service.",
				rationale:
					"Alongside the role endpoint the diff reorders and renames methods in UserService that the endpoint does not call.",
				citation: diff(USER_SERVICE, 88, 90, "private User requireActiveUser(long id) {"),
			},
			{
				practice: "describe-what-and-why",
				outcome: "MET",
				summary:
					"The description says why an admin needs to change a role and what happens to the old one.",
				citation: description(
					"An admin who promotes a member today has to delete and reinvite them.",
					3,
				),
			},
			{
				practice: "commit-subjects-explain-each-change",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "Four of six commit subjects say fix or address comments.",
				rationale:
					"Only the first two commits describe a change; the rest are fix-up commits left unsquashed.",
				citation: commits("address comments", 6),
			},
			{
				practice: "honours-linked-issue-acceptance-criteria",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "Closes an issue with four acceptance criteria and names none of them.",
				rationale:
					"The linked issue lists four criteria for role changes, including an audit entry, and the description does not say which of them the change meets.",
				citation: linkedIssues("Done when the change is written to the audit log.", 4),
			},
			{
				practice: "ships-tests-with-the-change",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "No test exercises the new role change.",
				rationale:
					"The diff adds the endpoint and the service method and no test; RoleControllerTest is untouched.",
				citation: diff(ROLE_CONTROLLER, 36, 37, '@PutMapping("/users/{id}/role")'),
			},
			{
				practice: "changes-dependencies-deliberately",
				outcome: "NOT_APPLICABLE",
				summary: "No dependency changed in this pull request.",
				citation: files("No build file in the diff."),
			},
		],
	},
	{
		key: "pr1-again",
		artifact: pullRequest(1),
		at: daysAgo(7, "09:20"),
		observations: [
			{
				practice: "leaves-useful-specific-review-comments",
				outcome: "MET",
				summary: "Each comment names the line and the change to make.",
				rationale:
					"The three comments on the notification service each quote the line, say what breaks and propose the replacement.",
				citation: reviewThreads(
					"Use the template engine here; the string concat skips escaping.",
					3,
				),
			},
		],
	},
	{
		key: "pr21",
		artifact: pullRequest(21),
		at: daysAgo(6, "14:35"),
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				outcome: "MET",
				summary: "Role inspection reads ids only and binds them.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/users/RoleQueryRepository.java",
					21,
					22,
					"WHERE u.id = :userId",
				),
			},
			{
				practice: "scope-one-reviewable-change",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "Role management carries a reformat of the user service.",
				rationale:
					"Nine files in the diff change only whitespace and import order in files the role feature does not touch.",
				citation: diff(USER_SERVICE, 1, 4, "import java.util.Optional;"),
			},
			{
				practice: "describe-what-and-why",
				outcome: "MET",
				summary: "The description explains who inspects roles and why.",
				citation: description(
					"Support needs to see a member's role without opening the database.",
					2,
				),
			},
			{
				practice: "ready-and-traceable-handoff",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "Marked ready without a link to its issue.",
				rationale:
					"The change was marked ready with no linked issue; the description explains the change but not which request it answers.",
				citation: linkedIssues("No linked issues."),
			},
			{
				practice: "ships-tests-with-the-change",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The role listing has no test.",
				rationale: "The diff adds the listing endpoint and touches no test file.",
				citation: diff(ROLE_CONTROLLER, 61, 62, '@GetMapping("/users/{id}/roles")'),
			},
			{
				practice: "changes-dependencies-deliberately",
				outcome: "NOT_APPLICABLE",
				summary: "No dependency changed in this pull request.",
				citation: files("No build file in the diff."),
			},
		],
	},
	{
		key: "pr23",
		artifact: pullRequest(23),
		at: daysAgo(5, "11:00"),
		observations: [
			{
				practice: "scope-one-reviewable-change",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The sweep ships with a rename of the deactivation service.",
				rationale:
					"Ten of the fourteen files rename DeactivationService to AccountLifecycle; the sweep itself is four files.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/users/AccountLifecycle.java",
					1,
					3,
					"public class AccountLifecycle {",
				),
			},
			{
				practice: "leaves-useful-specific-review-comments",
				outcome: "MET",
				summary: "The review names the boundary case and the fix for it.",
				citation: reviewThreads(
					"Accounts deactivated today would be swept too; compare against the day before the cut-off.",
					2,
				),
			},
		],
	},
	{
		key: "pr22",
		artifact: pullRequest(22),
		at: daysAgo(3, "16:45"),
		observations: [
			{
				practice: "scope-one-reviewable-change",
				outcome: "NOT_MET",
				severity: "MAJOR",
				summary: "The sign-in stamp arrives with a cleanup of the session store.",
				rationale:
					"The last sign-in column is three files; the other eleven rework SessionStore, which the stamp does not use.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/auth/SessionStore.java",
					14,
					16,
					"public final class SessionStore {",
				),
			},
			{
				practice: "ready-and-traceable-handoff",
				outcome: "MET",
				summary: "Marked ready with its issue linked and the draft label gone.",
				citation: linkedIssues("Closes the last sign-in issue."),
			},
			{
				practice: "commit-subjects-explain-each-change",
				outcome: "MET",
				summary: "Each commit subject says what it changes.",
				citation: commits("Record the last sign-in on every successful login", 3),
			},
			{
				practice: "ships-tests-with-the-change",
				outcome: "NOT_MET",
				severity: "MINOR",
				summary: "The sign-in stamp has no test that reads it back.",
				rationale:
					"The migration and the write are there; no test signs in and reads the stamp back.",
				citation: diff(
					"src/main/java/de/tum/cit/aet/auth/LoginSuccessHandler.java",
					29,
					30,
					"user.setLastSignedInAt(clock.instant());",
				),
			},
			{
				practice: "leaves-useful-specific-review-comments",
				outcome: "MET",
				summary: "The one comment names the line and the change.",
				citation: reviewThreads("Use the injected clock here so the test can pin the instant."),
			},
		],
	},
];

/**
 * The reader's in-app feedback: one card per practice, composed once a problem recurred on two pieces
 * of work, so the page shows each state a card takes. Two are new and unread. Two were read and are
 * still open, one of them disputed. Three were resolved by the work, as three clean pieces of work in a
 * row followed them; the reader also marked two of those addressed, but later than the work. One the
 * reader marked addressed before the work could, and one the reader marked not applicable.
 */
export const READER_CARDS: SeedCard[] = [
	{
		practice: "scope-one-reviewable-change",
		composedBy: "pr22",
		createdAt: daysAgo(1, "08:40"),
		headline: "Pull requests bundle a change with a cleanup",
		message:
			"In #19 the deactivation change travelled with a rename of the listing it lives beside, and #20, #21, #22 and #23 each carried a role, sign-in or sweep change alongside a cleanup or rename of the code around it. All five read like the tidy-up happened while the change was open, so the reviewer had to follow two intentions in one diff.",
		nextStep:
			"Next time a change and a cleanup meet in the same branch, open the change first as its own pull request, let it be reviewed alone, and put the cleanup on top of it once the change is in.",
		evidence: ["pr19", "pr20", "pr21", "pr23", "pr22"],
	},
	{
		practice: "ships-tests-with-the-change",
		composedBy: "pr22",
		createdAt: daysAgo(2, "16:05"),
		headline: "Behaviour changes arrive without a test for the new path",
		message:
			"#20, #21 and #22 each add a branch to the user service, a role change, a role listing and a last sign-in stamp, and none of them adds a test that exercises it. The descriptions name the case the change is for, so the missing test is the one the description already describes.",
		nextStep:
			"Before marking the pull request ready, add one test that feeds the case from the description through the new code path.",
		evidence: ["pr20", "pr21", "pr22"],
	},
	{
		practice: "ready-and-traceable-handoff",
		composedBy: "pr21",
		createdAt: daysAgo(5, "07:30"),
		deliveredAt: daysAgo(5, "09:12"),
		headline: "Changes were marked ready without the issue they close",
		message:
			"#19 and #21 were marked ready with no link to the issue that asked for them, and the reviewer on #19 opened with a question about what the listing was for. Both descriptions explain the change, so the missing piece is the one line that says which need it answers.",
		nextStep:
			"Before marking a change ready, add a Closes line that names the issue, so the reviewer knows why the change exists before reading it.",
		evidence: ["pr19", "pr21"],
	},
	{
		practice: "commit-subjects-explain-each-change",
		composedBy: "pr20",
		createdAt: daysAgo(8, "09:15"),
		deliveredAt: daysAgo(8, "11:40"),
		headline: "Commit subjects repeat the diff instead of the intent",
		message:
			'Five of the eight commits on #19 and four of the six on #20 read "fix", "wip" or "address comments", so the history of each pull request says nothing about which commit changed what. The pull request descriptions do say it, which means the words exist and only the commits are missing them.',
		nextStep:
			"Give each commit a subject that says what it changes and why, and squash the fix-up commits before you mark the pull request ready.",
		evidence: ["pr19", "pr20"],
		// Marked addressed after one clean piece of work, before the work could resolve it.
		response: { at: daysAgo(4, "10:30"), usefulness: "HELPFUL", resolution: "ADDRESSED" },
	},
	{
		practice: "honours-linked-issue-acceptance-criteria",
		composedBy: "pr20",
		createdAt: daysAgo(8, "09:15"),
		deliveredAt: daysAgo(7, "08:02"),
		headline: "Pull requests close their issue without saying which criteria are met",
		message:
			"#16 and #20 each close an issue that lists acceptance criteria and mention none of them, so the issue's author has to reread the diff to learn whether the change covers what was asked.",
		nextStep:
			"Copy the issue's acceptance criteria into the description and tick the ones the change meets, so the reviewer and the issue's author see the same list.",
		evidence: ["pr16", "pr20"],
		response: {
			at: daysAgo(7, "10:20"),
			resolution: "NOT_APPLICABLE",
			explanation:
				"#16 has no linked issue with acceptance criteria; the report runner was a spike we agreed on in the channel, so there was nothing to tick.",
		},
	},
	{
		practice: "issue-has-checkable-outcome",
		composedBy: "issue14",
		createdAt: daysAgo(19, "07:50"),
		deliveredAt: daysAgo(18, "08:31"),
		headline: "Issues describe the problem but not what done looks like",
		message:
			"#13 and #14 each explain what goes wrong and stop there. Neither says what a maintainer would check to close the issue, so the flaky test in #13 could be closed by a retry and the backoff in #14 by any delay at all.",
		nextStep:
			'End each issue with one sentence that starts with "Done when" and names the check a maintainer can run.',
		evidence: ["issue13", "issue14"],
		response: {
			at: daysAgo(17, "09:10"),
			usefulness: "UNHELPFUL",
			resolution: "DISPUTED",
			explanation:
				"Nobody has reproduced the flake in #13 yet, so a Done when line would be a guess. We add it once we know the cause.",
		},
	},
	{
		practice: "describe-what-and-why",
		composedBy: "pr17",
		createdAt: daysAgo(25, "10:05"),
		deliveredAt: daysAgo(25, "13:10"),
		headline: "Descriptions name the what, rarely the why",
		message:
			"#16 and #17 list the files touched and the endpoint added but not the problem behind them, and the reviewer on #17 asked in the first comment what deactivation was for. Both were written from the diff rather than from the need.",
		nextStep: "Before the file list, write one paragraph on the problem and the decision you took.",
		evidence: ["pr16", "pr17"],
		response: { at: daysAgo(5, "14:10"), usefulness: "HELPFUL", resolution: "ADDRESSED" },
	},
	{
		practice: "leaves-useful-specific-review-comments",
		composedBy: "pr4",
		createdAt: daysAgo(33, "15:30"),
		deliveredAt: daysAgo(32, "08:15"),
		headline: "Review comments say something is off, not what",
		message:
			'On #2 and #4 the comments read "this looks wrong" and "can we do better here?", and the author replied to each one asking what to change. The comments land on the right lines, so the missing half is the change you would make.',
		nextStep:
			"Name the line, say what is wrong with it and what you would do instead, so the author can act on the comment without asking back.",
		evidence: ["pr2", "pr4"],
		response: { at: daysAgo(2, "09:00"), resolution: "ADDRESSED" },
	},
	{
		practice: "validates-and-escapes-untrusted-input",
		composedBy: "pr16",
		createdAt: daysAgo(31, "11:20"),
		deliveredAt: daysAgo(31, "12:05"),
		headline: "Request fields reach a query or a template unescaped",
		message:
			"In #1 the notification body is built from the request's display name, in #2 and #4 the user lookup concatenates the login into the query, and in #16 the report runner passes the requested file name straight to the file system. Each change validates the field's presence and not its content.",
		nextStep:
			"Treat every request field as text until it is bound: use a parameter for the query, an encoder for the template and a whitelist for the path.",
		evidence: ["pr1", "pr2", "pr4", "pr16"],
		response: { at: daysAgo(5, "14:12"), usefulness: "HELPFUL", resolution: "ADDRESSED" },
	},
];

/** One card as `POST /api/dev/in-app-feedback` takes it (`DevInAppFeedbackService.Card`). */
export interface CardPayload {
	id: string;
	agentJobId: string;
	recipientUserId: number;
	practiceSlug: string;
	headline: string;
	message: string;
	nextStep: string;
	createdAt: string;
	deliveredAt: string | null;
	evidence: string[];
	response: {
		id: string;
		at: string;
		usefulness: string | null;
		resolution: string | null;
		explanation: string | null;
	} | null;
}

/**
 * The reader's cards as the server takes them, given the ids the seed wrote: `jobIds` by run key and
 * `observationIds` by run key and practice slug. A card that names a run the reader has no
 * observation on its practice from is a mistake in this file, and fails before anything is sent.
 */
export function readerCards(
	readerId: number,
	jobIds: ReadonlyMap<string, string>,
	observationIds: ReadonlyMap<string, string>,
): CardPayload[] {
	let responses = 0;
	return READER_CARDS.map((card, index) => {
		const agentJobId = jobIds.get(card.composedBy);
		if (agentJobId === undefined) {
			throw new Error(`The card on ${card.practice} names an unknown run ${card.composedBy}`);
		}
		const evidence = card.evidence.map((runKey) => {
			const id = observationIds.get(`${runKey}/${card.practice}`);
			if (id === undefined) {
				throw new Error(
					`The card on ${card.practice} cites run ${runKey}, which recorded nothing on it`,
				);
			}
			return id;
		});
		const { response } = card;
		if (response !== undefined) {
			responses += 1;
		}
		return {
			id: seedId(TABLE.feedback, index + 1),
			agentJobId,
			recipientUserId: readerId,
			practiceSlug: card.practice,
			headline: card.headline,
			message: card.message,
			nextStep: card.nextStep,
			createdAt: card.createdAt,
			deliveredAt: card.deliveredAt ?? null,
			evidence,
			response:
				response === undefined
					? null
					: {
							id: seedId(TABLE.reaction, responses),
							at: response.at,
							usefulness: response.usefulness ?? null,
							resolution: response.resolution ?? null,
							explanation: response.explanation ?? null,
						},
		};
	});
}
