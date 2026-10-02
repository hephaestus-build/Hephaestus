import type {
	ArtifactTrace,
	DeliveredWorkFeedback,
	PageResponseDtoObservationList,
	ReviewContext as ReviewContextDTO,
	Workspace,
	WorkspaceListItem,
} from "~/api/types.gen";
import { consentRequired, stale, WorkerError } from "~/background/errors";
import type { InstanceConfig } from "~/background/storage";
import {
	type ObservationPage,
	type ReadyContext,
	type ReviewContext,
	THE_PAGE,
	type WorkComment,
	type WorkFeedback,
	type WorkspaceChoice,
	type WorkSubject,
} from "~/shared/review-context";
import { workLinks } from "~/shared/web-app-links";
import {
	listTarget,
	parseListPage,
	parseWorkPage,
	type WorkPage,
	workPageLabel,
} from "~/shared/work-url";

/** The slice of `AuthenticatedApi` the resolver uses, so a test can stand in for the server. */
export interface ContextApi {
	readonly generation: number;
	assertCurrent: () => Promise<void>;
	workspaces: () => Promise<WorkspaceListItem[]>;
	workspace: (slug: string) => Promise<Workspace | undefined>;
	resolve: (slug: string, url: string) => Promise<ReviewContextDTO | undefined>;
	trace: (slug: string, kind: string, id: number) => Promise<ArtifactTrace | null>;
}

/** The reads behind the report on the work's own page: the reader's observations and comments. */
export interface DetailsApi extends ContextApi {
	ownObservations: (
		slug: string,
		kind: string,
		id: number,
		size: number,
	) => Promise<PageResponseDtoObservationList>;
	workFeedback: (slug: string, workUrl: string) => Promise<DeliveredWorkFeedback>;
}

export interface ContextEnvironment<TApi extends ContextApi = ContextApi> {
	instance: InstanceConfig;
	api: TApi;
	/** The tab's current address, read from Chrome at the moment of asking; never from a message. */
	tabUrl: (tabId: number) => Promise<string | undefined>;
	directory: WorkspaceDirectory;
	now: () => Date;
}

const PROVIDER_DEFAULT_ORIGINS = {
	GITHUB: "https://github.com",
	GITLAB: "https://gitlab.com",
} as const;

export interface WorkspaceSite extends WorkspaceChoice {
	providerType: "GITHUB" | "GITLAB";
	/** The provider origin the workspace is connected to. */
	siteOrigin: string;
}

function originOf(serverUrl: string | undefined, providerType: "GITHUB" | "GITLAB"): string {
	if (serverUrl === undefined || serverUrl === "") {
		return PROVIDER_DEFAULT_ORIGINS[providerType];
	}
	try {
		return new URL(serverUrl).origin;
	} catch {
		return PROVIDER_DEFAULT_ORIGINS[providerType];
	}
}

/**
 * Which provider site each of the account's workspaces is connected to. Kept per generation, so a new
 * sign-in, instance or revoked site starts from nothing; within one it saves a round trip per
 * workspace on every page.
 */
export class WorkspaceDirectory {
	#generation = -1;
	#sites: Promise<WorkspaceSite[]> | undefined;

	async sites(api: ContextApi): Promise<WorkspaceSite[]> {
		if (this.#generation !== api.generation || this.#sites === undefined) {
			this.#generation = api.generation;
			this.#sites = WorkspaceDirectory.#load(api);
		}
		const pending = this.#sites;
		try {
			return await pending;
		} catch (error) {
			// A failed load is not remembered; the next caller asks again.
			if (this.#sites === pending) {
				this.#sites = undefined;
			}
			throw error;
		}
	}

	clear(): void {
		this.#sites = undefined;
	}

	static async #load(api: ContextApi): Promise<WorkspaceSite[]> {
		const workspaces = await api.workspaces();
		const scm = workspaces.filter(
			(workspace): workspace is WorkspaceListItem & { providerType: "GITHUB" | "GITLAB" } =>
				workspace.providerType === "GITHUB" || workspace.providerType === "GITLAB",
		);
		const details = await Promise.all(
			scm.map(async (workspace) => api.workspace(workspace.workspaceSlug)),
		);
		return scm.map((workspace, index) => ({
			slug: workspace.workspaceSlug,
			displayName: workspace.displayName,
			providerType: workspace.providerType,
			siteOrigin: originOf(details[index]?.serverUrl, workspace.providerType),
		}));
	}
}

function workLabelFor(page: WorkPage): string {
	return `${page.repository} ${workPageLabel(page)}`;
}

/** Reads the tab again and fails `stale` if it no longer shows the work the request began with. */
async function assertSamePage(
	env: ContextEnvironment,
	tabId: number,
	page: WorkPage,
): Promise<void> {
	const current = parseWorkPage(await env.tabUrl(tabId));
	if (current?.canonicalUrl !== page.canonicalUrl) {
		throw stale();
	}
	await env.api.assertCurrent();
}

/** A list's address as the tab showed it, filters and page included; only the fragment is ignored. */
function listAddress(tabUrl: string): string {
	const url = new URL(tabUrl);
	url.hash = "";
	return url.href;
}

/**
 * Reads the tab again and fails `stale` unless it still shows the very list the row was on: another
 * filter or page of the same repository is another list, and its rows are not this one's.
 */
async function assertSameList(
	env: ContextEnvironment,
	tabId: number,
	address: string,
): Promise<void> {
	const current = await env.tabUrl(tabId);
	if (
		current === undefined ||
		parseListPage(current) === undefined ||
		listAddress(current) !== address
	) {
		throw stale();
	}
	await env.api.assertCurrent();
}

/** The work a request is about, and the check that the tab still shows it — or its list. */
interface Located {
	page: WorkPage;
	stillThere: () => Promise<void>;
}

/**
 * Which work a request is about, from the tab's actual address and nothing the frame says, except
 * which row of the tab's list: a list row counts only when it is exactly a canonical work address of
 * that same repository and kind.
 */
async function locate(
	env: ContextEnvironment,
	tabId: number,
	subject: WorkSubject,
): Promise<Located | undefined> {
	const tabUrl = await env.tabUrl(tabId);
	if (subject.kind === "page") {
		const page = parseWorkPage(tabUrl);
		return page === undefined
			? undefined
			: { page, stillThere: async () => assertSamePage(env, tabId, page) };
	}
	const list = parseListPage(tabUrl);
	const page = list === undefined ? undefined : listTarget(list, subject.url);
	if (tabUrl === undefined || page === undefined) {
		return undefined;
	}
	const address = listAddress(tabUrl);
	return { page, stillThere: async () => assertSameList(env, tabId, address) };
}

/**
 * What Hephaestus knows about the work in one tab, or in one row of the list the tab shows. The
 * address only picks which workspaces to ask; each workspace's server answers whether it is work it
 * monitors. Two workspaces that both know the work are a choice for the reader — never silently the
 * first.
 */
export async function resolveContext(
	env: ContextEnvironment,
	tabId: number,
	requestedSlug?: string,
	subject: WorkSubject = THE_PAGE,
): Promise<ReviewContext> {
	const { context } = await resolveLocated(env, tabId, requestedSlug, subject);
	return context;
}

async function resolveLocated(
	env: ContextEnvironment,
	tabId: number,
	requestedSlug: string | undefined,
	subject: WorkSubject,
): Promise<{ context: ReviewContext; located?: Located }> {
	const located = await locate(env, tabId, subject);
	if (located === undefined) {
		return { context: { status: "unsupported-page" } };
	}
	const { page } = located;
	const instanceHost = new URL(env.instance.origin).host;
	const allSites = await env.directory.sites(env.api);
	const sites = allSites.filter(
		(site) => site.providerType === page.provider && site.siteOrigin === page.origin,
	);
	await located.stillThere();
	if (sites.length === 0) {
		return { context: { status: "no-workspace", instanceHost, siteOrigin: page.origin } };
	}
	const answers = await Promise.all(
		sites.map(async (site) => ({
			site,
			dto: await env.api.resolve(site.slug, page.canonicalUrl),
		})),
	);
	await located.stillThere();
	const matches = answers.flatMap(({ site, dto }) => (dto === undefined ? [] : [{ site, dto }]));
	const workLabel = workLabelFor(page);
	if (matches.length === 0) {
		return { context: { status: "not-found", instanceHost, workLabel } };
	}
	const chosen =
		matches.find((match) => match.site.slug === requestedSlug) ??
		(matches.length === 1 ? matches[0] : undefined);
	const choices = matches.map(({ site }) => ({ slug: site.slug, displayName: site.displayName }));
	if (chosen === undefined) {
		return {
			context: { status: "choose-workspace", instanceHost, workLabel, candidates: choices },
		};
	}
	const { site, dto } = chosen;
	const trace = await env.api.trace(site.slug, dto.work.kind, Number(dto.work.id));
	await located.stillThere();
	return {
		located,
		context: {
			status: "ready",
			instanceHost,
			workspace: { slug: site.slug, displayName: site.displayName },
			alternatives: choices.filter((choice) => choice.slug !== site.slug),
			work: dto.work,
			canRequestReview: dto.canRequestReview,
			canInspectReviewDetails: dto.canInspectReviewDetails,
			trace,
			links: workLinks(env.instance.webAppOrigin, site.slug, dto.work, dto.canInspectReviewDetails),
			pageUrl: page.canonicalUrl,
			view: page.view,
			fetchedAt: env.now().toISOString(),
		},
	};
}

/** A resolved context, and the check that the tab still shows it — asked again before any reply. */
export interface ReadyFence {
	context: ReadyContext;
	stillShowing: () => Promise<void>;
}

/**
 * Resolves afresh in the requested workspace. The returned fence re-reads the tab immediately before
 * replying, so details of work the tab has navigated away from — or of a row of a list it has left —
 * are never returned.
 */
export async function requireReady(
	env: ContextEnvironment,
	tabId: number,
	workspaceSlug: string,
	subject: WorkSubject = THE_PAGE,
): Promise<ReadyFence> {
	const { context, located } = await resolveLocated(env, tabId, workspaceSlug, subject);
	if (context.status === "consent-required") {
		throw consentRequired();
	}
	if (
		context.status !== "ready" ||
		context.workspace.slug !== workspaceSlug ||
		located === undefined
	) {
		throw stale();
	}
	return { context, stillShowing: located.stillThere };
}

/** Whether a record the server returned is about the work the tab resolved to, exactly. */
export function sameWork(
	work: { kind: string; id: string | number } | undefined,
	context: Pick<ReadyContext, "work">,
): boolean {
	return (
		work !== undefined && work.kind === context.work.kind && String(work.id) === context.work.id
	);
}

/** Missing metadata is unknown, not proof that a downloaded first page is the complete result. */
function requiredTotal(total: number | undefined, returned: number): number {
	if (
		total === undefined ||
		!Number.isSafeInteger(total) ||
		total < returned ||
		(returned === 0 && total !== 0)
	) {
		throw new WorkerError(
			"server",
			"Hephaestus returned incomplete pagination information. Try loading again.",
		);
	}
	return total;
}

/** How many observations a report lists before it points to the web app for the rest. */
export const OBSERVATION_PAGE_SIZE = 25;

/**
 * Refuses a list with any row about other work. A server that predates the exact-work filter ignores
 * it and answers with the reader's whole feed; that must never be shown as this work's observations.
 */
function requireOnlyThisWork(
	works: readonly ({ kind: string; id: string | number } | undefined)[],
	context: ReadyContext,
): void {
	if (!works.every((work) => sameWork(work, context))) {
		throw new WorkerError(
			"server",
			"Hephaestus answered with records about other work, so none of them is shown. Open the work in Hephaestus instead.",
		);
	}
}

/**
 * The reader's own observations on the work, most severe first, projected field by field. Everyone,
 * admins included, reads only their own here; every developer's are the web app's.
 */
export async function observationPage(
	env: ContextEnvironment<DetailsApi>,
	tabId: number,
	workspaceSlug: string,
): Promise<ObservationPage> {
	const { context, stillShowing } = await requireReady(env, tabId, workspaceSlug);
	const answer = await env.api.ownObservations(
		workspaceSlug,
		context.work.kind,
		Number(context.work.id),
		OBSERVATION_PAGE_SIZE,
	);
	const content = answer.content ?? [];
	requireOnlyThisWork(
		content.map((row) => ({ kind: row.artifactKind, id: row.artifactId })),
		context,
	);
	await stillShowing();
	return {
		rows: content.map((row) => ({
			id: row.id,
			practiceName: row.practiceName,
			practiceSlug: row.practiceSlug,
			summary: row.summary,
			outcome: row.outcome,
			severity: row.severity,
			assessmentStatus: row.assessmentStatus,
			claimCurrentness: row.claimCurrentness,
			observedAt: row.observedAt,
		})),
		total: requiredTotal(answer.totalElements, content.length),
		fetchedAt: env.now().toISOString(),
	};
}

/**
 * A comment address the report may link to: a comment anchor on one of this work's own pages, at the
 * work's origin — GitLab's `issues` and `work_items` addresses of one issue both count. Anything else
 * loses its link.
 */
function verifiedPermalink(
	permalink: string | undefined,
	context: ReadyContext,
): string | undefined {
	if (permalink === undefined) {
		return undefined;
	}
	let url: URL;
	try {
		url = new URL(permalink);
	} catch {
		return undefined;
	}
	const page = parseWorkPage(url.href);
	const work = parseWorkPage(context.pageUrl);
	return page !== undefined &&
		work !== undefined &&
		url.hash.length > 1 &&
		page.origin === work.origin &&
		page.provider === work.provider &&
		page.repository === work.repository &&
		page.kind === work.kind &&
		page.number === work.number
		? url.href
		: undefined;
}

function newest(a: string | undefined, b: string | undefined): string | undefined {
	if (b === undefined || Number.isNaN(Date.parse(b))) {
		return a;
	}
	return a === undefined || Date.parse(b) > Date.parse(a) ? b : a;
}

/**
 * The comments Hephaestus recorded posting for the reader on the work, one per provider comment:
 * pieces of feedback that share a summary edited in place are one comment, with every practice any of
 * them named. The answer must be about this work; a comment link must lead to this work's pages. The
 * provider's comment handles stay in the worker.
 */
export async function workFeedback(
	env: ContextEnvironment<DetailsApi>,
	tabId: number,
	workspaceSlug: string,
	subject: WorkSubject = THE_PAGE,
): Promise<WorkFeedback> {
	const { context, stillShowing } = await requireReady(env, tabId, workspaceSlug, subject);
	const answer = await env.api.workFeedback(workspaceSlug, context.pageUrl);
	requireOnlyThisWork([answer.work], context);
	await stillShowing();
	const comments = new Map<string, WorkComment & { names: Map<string, string> }>();
	for (const item of answer.feedback) {
		for (const placement of item.placements) {
			const { type, commentRef } = placement;
			// Only a comment on the work itself is one the reader can go to; a blank handle identifies none.
			if ((type !== "SUMMARY" && type !== "INLINE") || commentRef.trim() === "") {
				continue;
			}
			const comment = comments.get(commentRef) ?? {
				kind: type,
				path: placement.path,
				startLine: placement.startLine,
				endLine: placement.endLine,
				permalink: verifiedPermalink(placement.permalink, context),
				practices: [],
				names: new Map<string, string>(),
			};
			for (const practice of item.practices) {
				comment.names.set(practice.slug, practice.name);
			}
			comment.permalink ??= verifiedPermalink(placement.permalink, context);
			comment.deliveredAt = newest(comment.deliveredAt, item.deliveredAt);
			comments.set(commentRef, comment);
		}
	}
	return {
		comments: [...comments.values()].map(({ names, ...comment }) => ({
			...comment,
			practices: [...names.values()].toSorted((a, b) => a.localeCompare(b)),
		})),
		more: answer.hasMore,
		fetchedAt: env.now().toISOString(),
	};
}
