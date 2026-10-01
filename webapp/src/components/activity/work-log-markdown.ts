import type { ActivityWork, WorkItem } from "@/api/types.gen";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";

import { actionPhrase, goneWork } from "./activity-kind-defs";

/**
 * Whose work a copy lists, and of which category, as its heading names it before the range:
 * "Reviews · Ada Lovelace", "Platform / Payments", "Activity" for your own.
 */
export function workLogTitle(...parts: readonly (string | undefined)[]): string {
	return parts.filter((part) => part !== undefined && hasText(part)).join(" · ");
}

export interface WorkLogMarkdownOptions {
	/** The heading after `##`: "Reviews · Ada Lovelace, 21–27 September 2026". */
	title: string;
	provider: ProviderType;
	/** Several people's work: each line ends with whom it counts for. */
	people: boolean;
}

/** The same list twice: Markdown for a text field, HTML for a rich one. */
export interface CopiedList {
	/** The Markdown. */
	text: string;
	html: string;
	/** How many pieces of work it lists. */
	count: number;
}

/**
 * "hephaestus-build/Hephaestus#2310", "aet/pipelines!42": a pull request or issue as its provider
 * writes a cross-repository reference, which is what a pasted line has to survive on its own.
 */
export function workReferenceWithOwner(
	work: Pick<WorkItem, "type" | "number" | "repository">,
	provider: ProviderType,
): string {
	const sigil = work.type === "PULL_REQUEST" ? getProviderTerms(provider).pullRequestSigil : "#";
	return `${work.repository?.nameWithOwner ?? ""}${sigil}${work.number}`;
}

/**
 * The facts after the title: where the work lives, what happened on it, and whose it was — the
 * names written by `name`, since only they are someone else's text.
 */
function details(
	item: ActivityWork,
	options: WorkLogMarkdownOptions,
	name: (text: string) => string,
): string[] {
	return [
		...(item.work ? [workReferenceWithOwner(item.work, options.provider)] : []),
		item.actions.map((action) => actionPhrase(action, options.provider)).join(", "),
		...(options.people && item.people.length > 0
			? [item.people.map((person) => name(person.name)).join(", ")]
			: []),
	];
}

/**
 * What Markdown would read as markup in text someone else wrote — a title, a name, a team in the
 * heading: emphasis, strikethrough, code, a link, an HTML tag, and an `&` that starts an entity. The
 * reference and the phrases are Hephaestus's own words and are written plainly, since a chat that
 * pastes Markdown as text would show every backslash.
 */
const MARKDOWN_SPECIAL = /[\\`*_~[\]<>]|&(?=#?\w+;)/gu;

const escapeMarkdown = (text: string): string => text.replaceAll(MARKDOWN_SPECIAL, String.raw`\$&`);

/** A URL inside `( )`: a bracket or a space in it would end the link early. */
const markdownUrl = (url: string): string =>
	url.replaceAll("(", "%28").replaceAll(")", "%29").replaceAll(" ", "%20");

const HTML_ENTITIES: Record<string, string> = {
	"&": "&amp;",
	"<": "&lt;",
	">": "&gt;",
	'"': "&quot;",
	"'": "&#39;",
};

const escapeHtml = (text: string): string =>
	text.replaceAll(/[&<>"']/gu, (char) => HTML_ENTITIES[char] ?? char);

function linkTarget(item: ActivityWork): string | undefined {
	const url = item.work?.htmlUrl;
	return hasText(url) ? url : undefined;
}

function titleOf(item: ActivityWork, provider: ProviderType): string {
	return (
		item.work?.title ??
		goneWork(
			item.actions.map((action) => action.kind),
			provider,
		)
	);
}

/**
 * A work log as a list to paste into a standup note, a 1:1 document or a brag document: one line per
 * piece of work, linked, with what happened on it in words. Work the provider no longer has keeps
 * its line without a link, so the counts stay true.
 */
export function workLogMarkdown(
	items: readonly ActivityWork[],
	options: WorkLogMarkdownOptions,
): CopiedList {
	const markdownLines = items.map((item) => {
		const title = escapeMarkdown(titleOf(item, options.provider));
		const url = linkTarget(item);
		const head = url === undefined ? title : `[${title}](${markdownUrl(url)})`;
		return `- ${[head, ...details(item, options, escapeMarkdown)].join(" · ")}`;
	});
	const htmlItems = items.map((item) => {
		const title = escapeHtml(titleOf(item, options.provider));
		const url = linkTarget(item);
		const head = url === undefined ? title : `<a href="${escapeHtml(url)}">${title}</a>`;
		return `<li>${[head, ...details(item, options, (text) => text).map(escapeHtml)].join(" · ")}</li>`;
	});
	return {
		text: [`## ${escapeMarkdown(options.title)}`, "", ...markdownLines].join("\n"),
		html: `<h2>${escapeHtml(options.title)}</h2><ul>${htmlItems.join("")}</ul>`,
		count: items.length,
	};
}
