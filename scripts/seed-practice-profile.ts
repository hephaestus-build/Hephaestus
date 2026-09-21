import { createHash } from "node:crypto";
import { join } from "node:path";
import process from "node:process";
import { parseArgs } from "node:util";

import { Client } from "pg";

import { positivePort, readEnvFile } from "./lib/env.ts";

/**
 * Seeds the development database with practice reviews of one developer's work, so the Practice
 * profile can be tested against the server rather than against fixtures: review runs on pull
 * requests and issues already synced into one workspace, the observations they recorded, and the
 * in-app feedback cards composed from the recurring problems, some open and some resolved. Nothing
 * here is addressed to a provider: the only feedback written is IN_APP, which is read on the
 * developer's own pages, and every run is already complete with both preparation lanes marked
 * done, so no sweeper, dispatcher or worker picks any of it up.
 *
 *     node scripts/seed-practice-profile.ts          # remove the seed's rows, then insert them
 *     node scripts/seed-practice-profile.ts reset    # remove the seed's rows only
 *
 * Whose work is seeded comes from the flags, or from the environment, or from the defaults the
 * `HephaestusTest` fixtures are synced under:
 *
 *     --workspace <slug>                  SEED_WORKSPACE_SLUG              hephaestustest
 *     --developer <login>                 SEED_DEVELOPER_LOGIN             ValentinGruener
 *     --pull-request-repository <owner/n> SEED_PULL_REQUEST_REPOSITORY     HephaestusTest/practice-validation
 *     --issue-repository <owner/name>     SEED_ISSUE_REPOSITORY            HephaestusTest/MaxTestRepo
 *
 * The runs name their pull requests and issues by number in those two repositories, so a
 * repository standing in for a default needs synced work under the same numbers.
 *
 * Every row the seed writes carries an id under {@link ID_PREFIX}, which is how the reset finds
 * them and how a re-run replaces them; nothing else in the database is touched. The reviewed work
 * is looked up by repository and number, never invented: a pull request or issue the workspace has
 * not synced fails the seed before it writes anything. The connection comes from `server/.env`
 * and only ever points at this machine.
 */

const { values: flags, positionals } = parseArgs({
	options: {
		workspace: { type: "string" },
		developer: { type: "string" },
		"pull-request-repository": { type: "string" },
		"issue-repository": { type: "string" },
	},
	allowPositionals: true,
});
const setting = (flag: keyof typeof flags, name: string, fallback: string): string =>
	flags[flag] ?? process.env[name] ?? fallback;

const WORKSPACE_SLUG = setting("workspace", "SEED_WORKSPACE_SLUG", "hephaestustest");
const DEVELOPER_LOGIN = setting("developer", "SEED_DEVELOPER_LOGIN", "ValentinGruener");
const PULL_REQUEST_REPOSITORY = setting(
	"pull-request-repository",
	"SEED_PULL_REQUEST_REPOSITORY",
	"HephaestusTest/practice-validation",
);
const ISSUE_REPOSITORY = setting(
	"issue-repository",
	"SEED_ISSUE_REPOSITORY",
	"HephaestusTest/MaxTestRepo",
);

/** A UUID v4 prefix no real row carries; the fourth group says which table the row is in. */
const ID_PREFIX = "5eed0000-cafe-4000";
const EVIDENCE_CONTRACT_VERSION = "1.0.0";
/** `FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE`: the band an in-app unit's position sits in. */
const IN_APP_POSITION_BASE = 7000;

type Presence = "PRESENT" | "ABSENT" | "NOT_APPLICABLE" | "INCONCLUSIVE";
type Assessment = "GOOD" | "BAD";
type Severity = "CRITICAL" | "MAJOR" | "MINOR" | "INFO";

interface ArtifactRef {
	kind: "scm.pull_request" | "scm.issue";
	repository: string;
	number: number;
}

interface Citation {
	sourceKind: string;
	path: string;
	startLine: number;
	endLine: number;
	quote: string;
	side?: "NEW" | "OLD";
}

interface SeedObservation {
	practice: string;
	presence: Presence;
	assessment?: Assessment;
	severity?: Severity;
	/** The observation's title on the timeline; at most 255 characters. */
	summary: string;
	/** Why it was noted. */
	rationale?: string;
	citation: Citation;
}

interface SeedRun {
	key: string;
	artifact: ArtifactRef;
	at: string;
	observations: SeedObservation[];
}

interface SeedResponse {
	at: string;
	usefulness?: "HELPFUL" | "UNHELPFUL";
	resolution?: "ADDRESSED" | "DISPUTED" | "NOT_APPLICABLE";
	comment?: string;
}

interface SeedCard {
	practice: string;
	/** The run whose cycle composed the card; its job owns the unit. */
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

const pullRequest = (number: number): ArtifactRef => ({
	kind: "scm.pull_request",
	repository: PULL_REQUEST_REPOSITORY,
	number,
});
const issue = (number: number): ArtifactRef => ({
	kind: "scm.issue",
	repository: ISSUE_REPOSITORY,
	number,
});

const diff = (path: string, startLine: number, endLine: number, quote: string): Citation => ({
	sourceKind: "scm.pull-request.diff",
	path,
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
 * The reviews, oldest first. Each run reviews one piece of work on one day and records what the
 * practices found there. The picture they draw over the developer's practice standings: untrusted
 * input improves across eight pull requests, scoping declines across eight, descriptions and review
 * comments recover after two problems, three practices stay mixed or in need of attention, and
 * dependency changes never come up.
 */
const RUNS: SeedRun[] = [
	{
		key: "pr1-aug",
		artifact: pullRequest(1),
		at: "2026-08-04T09:30:00Z",
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				presence: "PRESENT",
				assessment: "BAD",
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
				presence: "PRESENT",
				assessment: "GOOD",
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
		at: "2026-08-07T14:10:00Z",
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				presence: "PRESENT",
				assessment: "BAD",
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
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "One fix, one file, one intention.",
				rationale: "The diff touches the failing lookup and its test and nothing else.",
				citation: description("Fixes the user lookup that failed for logins with a dot."),
			},
			{
				practice: "leaves-useful-specific-review-comments",
				presence: "PRESENT",
				assessment: "BAD",
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
		at: "2026-08-11T10:45:00Z",
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				presence: "PRESENT",
				assessment: "BAD",
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
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "The refactor stays inside the error handling it names.",
				rationale:
					"Every hunk replaces a return code with a domain exception; no behaviour change rides along.",
				citation: description("Replaces the error codes in UserService with domain exceptions."),
			},
			{
				practice: "leaves-useful-specific-review-comments",
				presence: "PRESENT",
				assessment: "BAD",
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
		at: "2026-08-13T16:20:00Z",
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				presence: "PRESENT",
				assessment: "BAD",
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
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "The runner arrives on its own.",
				citation: description("Adds a report runner that executes one report definition."),
			},
			{
				practice: "describe-what-and-why",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MAJOR",
				summary: "The description lists the files touched and not the problem behind them.",
				rationale:
					"The description names ReportRunner and its test and says nothing about why a runner is needed or what it replaces.",
				citation: description("Adds ReportRunner and ReportRunnerTest.", 2),
			},
			{
				practice: "honours-linked-issue-acceptance-criteria",
				presence: "ABSENT",
				assessment: "BAD",
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
		at: "2026-08-18T09:05:00Z",
		observations: [
			{
				practice: "issue-has-checkable-outcome",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MINOR",
				summary: "The issue says the test is flaky and not what fixed looks like.",
				rationale:
					"The issue describes the failing integration test and how often it fails, and ends without a condition a maintainer could check before closing it.",
				citation: issueBody("The integration test fails about one run in five on CI.", 6),
			},
			{
				practice: "issue-states-an-actionable-problem",
				presence: "PRESENT",
				assessment: "GOOD",
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
		at: "2026-08-19T11:50:00Z",
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				presence: "ABSENT",
				assessment: "GOOD",
				summary: "The deactivation endpoint validates the id and binds it as a parameter.",
				rationale:
					"The id is parsed as a number before the lookup and the lookup binds it; nothing from the request reaches a sink as text.",
				citation: diff(USER_CONTROLLER, 33, 35, "@PathVariable long userId"),
			},
			{
				practice: "scope-one-reviewable-change",
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "One endpoint, one intention.",
				citation: description("Adds POST /users/{id}/deactivate."),
			},
			{
				practice: "describe-what-and-why",
				presence: "ABSENT",
				assessment: "BAD",
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
		at: "2026-08-25T13:25:00Z",
		observations: [
			{
				practice: "issue-has-checkable-outcome",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MINOR",
				summary: "The issue asks for backoff without saying how a maintainer would verify it.",
				rationale:
					"The body asks for rate-limit backoff on the GraphQL client and names no limit, no retry budget and no check.",
				citation: issueBody("The GraphQL client should back off when GitHub rate limits us.", 4),
			},
			{
				practice: "issue-states-an-actionable-problem",
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "The issue states the failure a maintainer can reproduce.",
				citation: issueBody("Sync jobs fail with 403 once the hourly budget is spent.", 2),
			},
		],
	},
	{
		key: "pr19",
		artifact: pullRequest(19),
		at: "2026-08-27T15:40:00Z",
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				presence: "ABSENT",
				assessment: "GOOD",
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
				presence: "PRESENT",
				assessment: "BAD",
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
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "The description opens with the support request the listing answers.",
				citation: description(
					"Support asked for a way to see who is still active before a deactivation sweep.",
					2,
				),
			},
			{
				practice: "ready-and-traceable-handoff",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MINOR",
				summary: "Marked ready with no link to the issue that asked for the listing.",
				rationale:
					"The pull request was marked ready without a linked issue, and the reviewer opened with a question about the listing's purpose.",
				citation: linkedIssues("No linked issues."),
			},
			{
				practice: "commit-subjects-explain-each-change",
				presence: "PRESENT",
				assessment: "BAD",
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
		at: "2026-09-05T10:15:00Z",
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				presence: "ABSENT",
				assessment: "GOOD",
				summary: "The new role is validated against the enum before it is stored.",
				citation: diff(ROLE_CONTROLLER, 40, 41, "Role role = Role.parse(request.role());"),
			},
			{
				practice: "scope-one-reviewable-change",
				presence: "PRESENT",
				assessment: "BAD",
				severity: "MAJOR",
				summary: "The role change ships with a cleanup of the user service.",
				rationale:
					"Alongside the role endpoint the diff reorders and renames methods in UserService that the endpoint does not call.",
				citation: diff(USER_SERVICE, 88, 90, "private User requireActiveUser(long id) {"),
			},
			{
				practice: "describe-what-and-why",
				presence: "PRESENT",
				assessment: "GOOD",
				summary:
					"The description says why an admin needs to change a role and what happens to the old one.",
				citation: description(
					"An admin who promotes a member today has to delete and reinvite them.",
					3,
				),
			},
			{
				practice: "commit-subjects-explain-each-change",
				presence: "PRESENT",
				assessment: "BAD",
				severity: "MAJOR",
				summary: "Four of six commit subjects say fix or address comments.",
				rationale:
					"Only the first two commits describe a change; the rest are fix-up commits left unsquashed.",
				citation: commits("address comments", 6),
			},
			{
				practice: "honours-linked-issue-acceptance-criteria",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MAJOR",
				summary: "Closes an issue with four acceptance criteria and names none of them.",
				rationale:
					"The linked issue lists four criteria for role changes, including an audit entry, and the description does not say which of them the change meets.",
				citation: linkedIssues("Done when the change is written to the audit log.", 4),
			},
			{
				practice: "ships-tests-with-the-change",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MAJOR",
				summary: "No test exercises the new role change.",
				rationale:
					"The diff adds the endpoint and the service method and no test; RoleControllerTest is untouched.",
				citation: diff(ROLE_CONTROLLER, 36, 37, '@PutMapping("/users/{id}/role")'),
			},
			{
				practice: "changes-dependencies-deliberately",
				presence: "NOT_APPLICABLE",
				summary: "No dependency changed in this pull request.",
				citation: files("No build file in the diff."),
			},
		],
	},
	{
		key: "pr1-sep",
		artifact: pullRequest(1),
		at: "2026-09-07T09:20:00Z",
		observations: [
			{
				practice: "leaves-useful-specific-review-comments",
				presence: "PRESENT",
				assessment: "GOOD",
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
		at: "2026-09-08T14:35:00Z",
		observations: [
			{
				practice: "validates-and-escapes-untrusted-input",
				presence: "ABSENT",
				assessment: "GOOD",
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
				presence: "PRESENT",
				assessment: "BAD",
				severity: "MINOR",
				summary: "Role management carries a reformat of the user service.",
				rationale:
					"Nine files in the diff change only whitespace and import order in files the role feature does not touch.",
				citation: diff(USER_SERVICE, 1, 4, "import java.util.Optional;"),
			},
			{
				practice: "describe-what-and-why",
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "The description explains who inspects roles and why.",
				citation: description(
					"Support needs to see a member's role without opening the database.",
					2,
				),
			},
			{
				practice: "ready-and-traceable-handoff",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MINOR",
				summary: "Marked ready without a link to its issue.",
				rationale:
					"The change was marked ready with no linked issue; the description explains the change but not which request it answers.",
				citation: linkedIssues("No linked issues."),
			},
			{
				practice: "ships-tests-with-the-change",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MAJOR",
				summary: "The role listing has no test.",
				rationale: "The diff adds the listing endpoint and touches no test file.",
				citation: diff(ROLE_CONTROLLER, 61, 62, '@GetMapping("/users/{id}/roles")'),
			},
			{
				practice: "changes-dependencies-deliberately",
				presence: "NOT_APPLICABLE",
				summary: "No dependency changed in this pull request.",
				citation: files("No build file in the diff."),
			},
		],
	},
	{
		key: "pr23",
		artifact: pullRequest(23),
		at: "2026-09-09T11:00:00Z",
		observations: [
			{
				practice: "scope-one-reviewable-change",
				presence: "PRESENT",
				assessment: "BAD",
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
				presence: "PRESENT",
				assessment: "GOOD",
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
		at: "2026-09-11T16:45:00Z",
		observations: [
			{
				practice: "scope-one-reviewable-change",
				presence: "PRESENT",
				assessment: "BAD",
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
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "Marked ready with its issue linked and the draft label gone.",
				citation: linkedIssues("Closes the last sign-in issue."),
			},
			{
				practice: "commit-subjects-explain-each-change",
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "Each commit subject says what it changes.",
				citation: commits("Record the last sign-in on every successful login", 3),
			},
			{
				practice: "ships-tests-with-the-change",
				presence: "ABSENT",
				assessment: "BAD",
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
				presence: "PRESENT",
				assessment: "GOOD",
				summary: "The one comment names the line and the change.",
				citation: reviewThreads("Use the injected clock here so the test can pin the instant."),
			},
		],
	},
];

/**
 * The in-app feedback: one live card per practice, composed once a problem recurred on two pieces
 * of work. Two are unread, two were read, one was rated helpful, one was disputed, and three were
 * marked addressed once the work came back clean.
 */
const CARDS: SeedCard[] = [
	{
		practice: "scope-one-reviewable-change",
		composedBy: "pr22",
		createdAt: "2026-09-13T08:40:00Z",
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
		createdAt: "2026-09-12T16:05:00Z",
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
		createdAt: "2026-09-09T07:30:00Z",
		deliveredAt: "2026-09-09T09:12:00Z",
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
		createdAt: "2026-09-06T09:15:00Z",
		deliveredAt: "2026-09-06T11:40:00Z",
		headline: "Commit subjects repeat the diff instead of the intent",
		message:
			'Five of the eight commits on #19 and four of the six on #20 read "fix", "wip" or "address comments", so the history of each pull request says nothing about which commit changed what. The pull request descriptions do say it, which means the words exist and only the commits are missing them.',
		nextStep:
			"Give each commit a subject that says what it changes and why, and squash the fix-up commits before you mark the pull request ready.",
		evidence: ["pr19", "pr20"],
		response: { at: "2026-09-06T11:44:00Z", usefulness: "HELPFUL" },
	},
	{
		practice: "honours-linked-issue-acceptance-criteria",
		composedBy: "pr20",
		createdAt: "2026-09-06T09:15:00Z",
		deliveredAt: "2026-09-07T08:02:00Z",
		headline: "Pull requests close their issue without saying which criteria are met",
		message:
			"#16 and #20 each close an issue that lists acceptance criteria and mention none of them, so the issue's author has to reread the diff to learn whether the change covers what was asked.",
		nextStep:
			"Copy the issue's acceptance criteria into the description and tick the ones the change meets, so the reviewer and the issue's author see the same list.",
		evidence: ["pr16", "pr20"],
		response: {
			at: "2026-09-07T10:20:00Z",
			usefulness: "UNHELPFUL",
			resolution: "DISPUTED",
			comment:
				"#16 has no linked issue with acceptance criteria; the report runner was a spike we agreed on in the channel, so there was nothing to tick.",
		},
	},
	{
		practice: "issue-has-checkable-outcome",
		composedBy: "issue14",
		createdAt: "2026-08-26T07:50:00Z",
		deliveredAt: "2026-08-27T08:31:00Z",
		headline: "Issues describe the problem but not what done looks like",
		message:
			"#13 and #14 each explain what goes wrong and stop there. Neither says what a maintainer would check to close the issue, so the flaky test in #13 could be closed by a retry and the backoff in #14 by any delay at all.",
		nextStep:
			'End each issue with one sentence that starts with "Done when" and names the check a maintainer can run.',
		evidence: ["issue13", "issue14"],
	},
	{
		practice: "describe-what-and-why",
		composedBy: "pr17",
		createdAt: "2026-08-20T10:05:00Z",
		deliveredAt: "2026-08-20T13:10:00Z",
		headline: "Descriptions name the what, rarely the why",
		message:
			"#16 and #17 list the files touched and the endpoint added but not the problem behind them, and the reviewer on #17 asked in the first comment what deactivation was for. Both were written from the diff rather than from the need.",
		nextStep: "Before the file list, write one paragraph on the problem and the decision you took.",
		evidence: ["pr16", "pr17"],
		response: { at: "2026-09-09T14:10:00Z", usefulness: "HELPFUL", resolution: "ADDRESSED" },
	},
	{
		practice: "leaves-useful-specific-review-comments",
		composedBy: "pr4",
		createdAt: "2026-08-12T15:30:00Z",
		deliveredAt: "2026-08-13T08:15:00Z",
		headline: "Review comments say something is off, not what",
		message:
			'On #2 and #4 the comments read "this looks wrong" and "can we do better here?", and the author replied to each one asking what to change. The comments land on the right lines, so the missing half is the change you would make.',
		nextStep:
			"Name the line, say what is wrong with it and what you would do instead, so the author can act on the comment without asking back.",
		evidence: ["pr2", "pr4"],
		response: { at: "2026-09-12T09:00:00Z", resolution: "ADDRESSED" },
	},
	{
		practice: "validates-and-escapes-untrusted-input",
		composedBy: "pr16",
		createdAt: "2026-08-14T11:20:00Z",
		deliveredAt: "2026-08-14T12:05:00Z",
		headline: "Request fields reach a query or a template unescaped",
		message:
			"In #1 the notification body is built from the request's display name, in #2 and #4 the user lookup concatenates the login into the query, and in #16 the report runner passes the requested file name straight to the file system. Each change validates the field's presence and not its content.",
		nextStep:
			"Treat every request field as text until it is bound: use a parameter for the query, an encoder for the template and a whitelist for the path.",
		evidence: ["pr1-aug", "pr2", "pr4", "pr16"],
		response: { at: "2026-09-09T14:12:00Z", usefulness: "HELPFUL", resolution: "ADDRESSED" },
	},
];

// --- the rows -------------------------------------------------------------------------------------

/** The fourth UUID group names the table a seeded row lives in. */
const TABLE = {
	job: "8000",
	observation: "8001",
	feedback: "8002",
	reaction: "8003",
} as const;

function seedId(table: (typeof TABLE)[keyof typeof TABLE], ordinal: number): string {
	return `${ID_PREFIX}-${table}-${ordinal.toString(16).padStart(12, "0")}`;
}

/** `FeedbackThreadKey`: the same canonical form and digest, so the server's lookups find these threads. */
function threadKey(kind: string, locus: string, recipientUserId: number, channel: string): string {
	// The ASCII unit separator the Java side joins the tuple with.
	const separator = "\u001F";
	return createHash("sha256")
		.update(
			`${kind}${separator}${locus}${separator}${recipientUserId}${separator}${channel}`,
			"utf8",
		)
		.digest("hex");
}

/** `InAppFeedbackBody.render`: the stored layout the in-app reader splits again. */
function inAppBody(card: SeedCard): string {
	return `### ${card.headline}\n\n${card.message}\n\n**Try next:** ${card.nextStep}`;
}

interface Artifact {
	id: number;
	number: number;
	title: string;
	url: string;
	repository: string;
	kind: ArtifactRef["kind"];
}

interface Practice {
	id: number;
	revisionId: number;
}

interface Resolved {
	workspaceId: number;
	developerId: number;
	practices: Map<string, Practice>;
	artifacts: Map<string, Artifact>;
}

const artifactKey = (ref: ArtifactRef): string => `${ref.kind}:${ref.repository}#${ref.number}`;

async function resolve(client: Client): Promise<Resolved> {
	const workspace = await client.query<{ id: number }>("SELECT id FROM workspace WHERE slug = $1", [
		WORKSPACE_SLUG,
	]);
	const workspaceId = workspace.rows[0]?.id;
	if (workspaceId === undefined) throw new Error(`No workspace with slug ${WORKSPACE_SLUG}`);

	const developer = await client.query<{ id: number }>('SELECT id FROM "user" WHERE login = $1', [
		DEVELOPER_LOGIN,
	]);
	const developerId = developer.rows[0]?.id;
	if (developerId === undefined) throw new Error(`No synced user with login ${DEVELOPER_LOGIN}`);

	const slugs = [
		...new Set(
			[...RUNS.flatMap((run) => run.observations), ...CARDS].map((entry) => entry.practice),
		),
	];
	const practiceRows = await client.query<{ slug: string; id: number; revision_id: number | null }>(
		"SELECT slug, id, current_revision_id AS revision_id FROM practice WHERE workspace_id = $1 AND slug = ANY($2)",
		[workspaceId, slugs],
	);
	const practices = new Map<string, Practice>();
	for (const row of practiceRows.rows) {
		if (row.revision_id === null) throw new Error(`Practice ${row.slug} has no current revision`);
		practices.set(row.slug, { id: row.id, revisionId: row.revision_id });
	}
	const missingPractices = slugs.filter((slug) => !practices.has(slug));
	if (missingPractices.length > 0) {
		throw new Error(`Practices not installed in ${WORKSPACE_SLUG}: ${missingPractices.join(", ")}`);
	}

	const refs = new Map(RUNS.map((run) => [artifactKey(run.artifact), run.artifact]));
	const artifacts = new Map<string, Artifact>();
	for (const [key, ref] of refs) {
		const rows = await client.query<{ id: number; title: string; html_url: string }>(
			`SELECT i.id, i.title, i.html_url FROM issue i
			 JOIN repository r ON r.id = i.repository_id
			 WHERE r.name_with_owner = $1 AND i.number = $2 AND i.issue_type = $3`,
			[ref.repository, ref.number, ref.kind === "scm.pull_request" ? "PULL_REQUEST" : "ISSUE"],
		);
		const row = rows.rows[0];
		if (!row) throw new Error(`${ref.repository}#${ref.number} is not synced into this database`);
		artifacts.set(key, {
			id: row.id,
			number: ref.number,
			title: row.title,
			url: row.html_url,
			repository: ref.repository,
			kind: ref.kind,
		});
	}
	return { workspaceId, developerId, practices, artifacts };
}

async function removeSeed(client: Client): Promise<void> {
	const pattern = `${ID_PREFIX}-%`;
	// Children first: the observation and feedback foreign keys onto agent_job are RESTRICT.
	await client.query(
		"DELETE FROM reaction WHERE id::text LIKE $1 OR feedback_id IN (SELECT id FROM feedback WHERE id::text LIKE $1)",
		[pattern],
	);
	await client.query("DELETE FROM feedback WHERE id::text LIKE $1", [pattern]);
	await client.query("DELETE FROM observation WHERE id::text LIKE $1", [pattern]);
	await client.query("DELETE FROM agent_job WHERE id::text LIKE $1", [pattern]);
}

interface Counts {
	agent_job: number;
	observation: number;
	feedback: number;
	feedback_observation: number;
	reaction: number;
}

async function insertSeed(client: Client, resolved: Resolved): Promise<Counts> {
	const counts: Counts = {
		agent_job: 0,
		observation: 0,
		feedback: 0,
		feedback_observation: 0,
		reaction: 0,
	};
	const { workspaceId, developerId } = resolved;
	const jobIds = new Map<string, string>();
	/** Observation id by run key and practice slug, for the cards' evidence bindings. */
	const observationIds = new Map<string, string>();
	let observationOrdinal = 0;
	let feedbackOrdinal = 0;

	for (const [runIndex, run] of RUNS.entries()) {
		const artifact = resolved.artifacts.get(artifactKey(run.artifact));
		if (!artifact) throw new Error(`Unresolved artifact for run ${run.key}`);
		const jobId = seedId(TABLE.job, runIndex + 1);
		jobIds.set(run.key, jobId);
		const isPullRequest = artifact.kind === "scm.pull_request";
		const metadata = isPullRequest
			? {
					title: artifact.title,
					pr_url: artifact.url,
					pr_number: artifact.number,
					pull_request_id: artifact.id,
					repository_full_name: artifact.repository,
				}
			: {
					title: artifact.title,
					issue_url: artifact.url,
					issue_number: artifact.number,
					issue_id: artifact.id,
					repository_full_name: artifact.repository,
				};
		const startedAt = new Date(new Date(run.at).getTime() - 4 * 60_000).toISOString();
		await client.query(
			`INSERT INTO agent_job (
				id, workspace_id, job_type, status, metadata, output, config_snapshot, job_token, retry_count,
				created_at, started_at, completed_at, integration_kind, artifact_kind, available_at,
				delivery_attempts, purpose, evidence_snapshot, in_chat_prepared_at, in_app_prepared_at,
				practice_rollout_revision, practice_trigger_mode, trace_id
			) VALUES (
				$1, $2, $3, 'COMPLETED', $4, '{"outcome": "REVIEWED"}', '{}', $5, 0,
				$6, $6, $7, 'GITHUB', $8, $6,
				0, 'PRACTICE_REVIEW', $9, $7, $7,
				0, 'AUTO', $10
			)`,
			[
				jobId,
				workspaceId,
				isPullRequest ? "PULL_REQUEST_REVIEW" : "ISSUE_REVIEW",
				JSON.stringify(metadata),
				`seed-practice-profile-${run.key}`,
				startedAt,
				run.at,
				artifact.kind,
				JSON.stringify({ manifest: { contractVersion: EVIDENCE_CONTRACT_VERSION } }),
				jobId.replaceAll("-", "").slice(0, 32),
			],
		);
		counts.agent_job++;

		for (const observation of run.observations) {
			const practice = resolved.practices.get(observation.practice);
			if (!practice) throw new Error(`Unresolved practice ${observation.practice}`);
			const observationId = seedId(TABLE.observation, ++observationOrdinal);
			observationIds.set(`${run.key}/${observation.practice}`, observationId);
			const citation = {
				sourceKind: observation.citation.sourceKind,
				artifactPath:
					observation.citation.sourceKind === "scm.pull-request.diff"
						? "inputs/context/diff.patch"
						: `${artifact.repository}#${artifact.number}`,
				path: observation.citation.path,
				...(observation.citation.side ? { side: observation.citation.side } : {}),
				startLine: observation.citation.startLine,
				endLine: observation.citation.endLine,
				quote: observation.citation.quote,
				quoteRedacted: false,
			};
			await client.query(
				`INSERT INTO observation (
					id, occurrence_key, agent_job_id, practice_id, artifact_kind, artifact_id, about_user_id,
					summary, presence, severity, evidence, evidence_rationale, observed_at,
					practice_revision_id, assessment, origin, workspace_id
				) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, 'LIVE', $16)`,
				[
					observationId,
					`seed-practice-profile-${observationOrdinal}`,
					jobId,
					practice.id,
					artifact.kind,
					artifact.id,
					developerId,
					observation.summary,
					observation.presence,
					observation.severity ?? null,
					JSON.stringify({ detector: "practice-observer", citations: [citation] }),
					observation.rationale ?? null,
					run.at,
					practice.revisionId,
					observation.assessment ?? null,
					workspaceId,
				],
			);
			counts.observation++;
		}
	}

	const inAppPositions = new Map<string, number>();
	let reactionOrdinal = 0;
	for (const card of CARDS) {
		const jobId = jobIds.get(card.composedBy);
		if (!jobId) throw new Error(`Card on ${card.practice} names an unknown run ${card.composedBy}`);
		const position = IN_APP_POSITION_BASE + (inAppPositions.get(jobId) ?? 0);
		inAppPositions.set(jobId, position - IN_APP_POSITION_BASE + 1);
		const feedbackId = seedId(TABLE.feedback, ++feedbackOrdinal);
		await client.query(
			`INSERT INTO feedback (
				id, agent_job_id, workspace_id, recipient_user_id, about_user_id, channel, position,
				delivery_state, body, source, thread_key, created_at, delivered_at,
				proposed_placements, proposed_practice_slugs
			) VALUES ($1, $2, $3, $4, $4, 'IN_APP', $5, $6, $7, 'AGENT', $8, $9, $10, '[]', $11)`,
			[
				feedbackId,
				jobId,
				workspaceId,
				developerId,
				position,
				card.deliveredAt ? "DELIVERED" : "PREPARED",
				inAppBody(card),
				threadKey("practice", card.practice, developerId, "IN_APP"),
				card.createdAt,
				card.deliveredAt ?? null,
				JSON.stringify([card.practice]),
			],
		);
		counts.feedback++;
		for (const [ordinal, runKey] of card.evidence.entries()) {
			const observationId = observationIds.get(`${runKey}/${card.practice}`);
			if (!observationId) {
				throw new Error(
					`Card on ${card.practice} cites run ${runKey}, which recorded nothing on it`,
				);
			}
			await client.query(
				"INSERT INTO feedback_observation (feedback_id, observation_id, role, ordinal) VALUES ($1, $2, 'PRIMARY', $3)",
				[feedbackId, observationId, ordinal],
			);
			counts.feedback_observation++;
		}
		if (card.response) {
			if (!card.deliveredAt)
				throw new Error(`Card on ${card.practice} was answered before it was read`);
			await client.query(
				`INSERT INTO reaction (id, reactor_user_id, action, explanation, created_at, feedback_id, usefulness)
				 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
				[
					seedId(TABLE.reaction, ++reactionOrdinal),
					developerId,
					card.response.resolution ?? null,
					card.response.comment ?? null,
					card.response.at,
					feedbackId,
					card.response.usefulness ?? null,
				],
			);
			counts.reaction++;
		}
	}
	return counts;
}

async function main(): Promise<void> {
	const [mode = "seed", ...rest] = positionals;
	if ((mode !== "seed" && mode !== "reset") || rest.length > 0) {
		throw new Error(`Unknown mode ${positionals.join(" ")}; use "seed" (the default) or "reset"`);
	}
	const server = join(import.meta.dirname, "..", "server");
	const env = { ...(await readEnvFile(join(server, ".env"))), ...process.env };
	const host = env.POSTGRES_HOST ?? "localhost";
	// The seed writes straight into the database, so it refuses every host but this machine's.
	// The loopback rule lives in `scripts/e2e-setup.ts`, on E2E_DB_URL; this is the same list.
	if (!["localhost", "127.0.0.1", "[::1]", "::1"].includes(host)) {
		throw new Error("POSTGRES_HOST must be a loopback address");
	}
	const client = new Client({
		host,
		port: positivePort(env.POSTGRES_PORT ?? "5432", "POSTGRES_PORT"),
		database: env.POSTGRES_DB ?? "hephaestus",
		user: env.DB_USERNAME ?? "root",
		password: env.DB_PASSWORD ?? "root",
	});
	await client.connect();
	try {
		await client.query("BEGIN");
		await removeSeed(client);
		if (mode === "reset") {
			await client.query("COMMIT");
			console.log("Removed the practice profile seed.");
			return;
		}
		const resolved = await resolve(client);
		const counts = await insertSeed(client, resolved);
		await client.query("COMMIT");
		const written = Object.entries(counts)
			.map(([table, count]) => `${count} ${table}`)
			.join(", ");
		console.log(
			`Seeded the practice profile of ${DEVELOPER_LOGIN} in ${WORKSPACE_SLUG}: ${written}`,
		);
	} catch (error) {
		await client.query("ROLLBACK").catch(() => undefined);
		throw error;
	} finally {
		await client.end();
	}
}

await main();
