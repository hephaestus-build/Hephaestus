import { experimental_useSearch as useSearch } from "fumadocs-core/search/client";
import {
	SearchDialog,
	SearchDialogClose,
	SearchDialogContent,
	SearchDialogHeader,
	SearchDialogIcon,
	SearchDialogInput,
	SearchDialogList,
	SearchDialogOverlay,
	type SharedProps,
} from "fumadocs-ui/components/dialog/search";

import { BASE } from "@/lib/site";

interface PagefindPage {
	url: string;
	title: string;
	headings: { title: string; url: string }[];
}
type Search = (query: string) => Promise<PagefindPage[]>;

const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null;
const isString = (value: unknown): value is string => typeof value === "string";
// Pagefind's documented signatures: `search(query)` and each result's `data()` return promises.
const isSearch = (value: unknown): value is (query: string) => Promise<unknown> =>
	typeof value === "function";
const isLoader = (value: unknown): value is () => Promise<unknown> => typeof value === "function";

/* The module and its results come from a file at run time, so each value is checked before use. */
function page(data: unknown): PagefindPage | undefined {
	if (
		!isRecord(data) ||
		!isString(data.url) ||
		!isRecord(data.meta) ||
		!Array.isArray(data.sub_results)
	) {
		return undefined;
	}
	const headings = data.sub_results.flatMap((sub: unknown) =>
		isRecord(sub) && isString(sub.title) && isString(sub.url)
			? [{ title: sub.title, url: sub.url }]
			: [],
	);
	return { url: data.url, title: isString(data.meta.title) ? data.meta.title : data.url, headings };
}

async function importPagefind(): Promise<Search> {
	const module: unknown = await import(
		// The index is a file of the build, not a module of the bundle.
		/* @vite-ignore */
		`${BASE}pagefind/pagefind.js`
	);
	if (!isRecord(module) || !isSearch(module.search)) {
		throw new Error("The Pagefind module has no search function.");
	}
	const { search } = module;
	return async (query) => {
		const found: unknown = await search(query);
		const results =
			isRecord(found) && Array.isArray(found.results) ? found.results.slice(0, 8) : [];
		const pages = await Promise.all(
			results.map(async (result: unknown) =>
				isRecord(result) && isLoader(result.data) ? page(await result.data()) : undefined,
			),
		);
		return pages.filter((entry) => entry !== undefined);
	};
}

let pagefind: Promise<Search> | undefined;

/*
 * The build writes a Pagefind index of the static pages (`pagefind --site dist`). The browser loads only the chunks
 * that a query needs, with no hosted service (ADR 0111). The index is not in the dev server.
 */
async function loadPagefind(): Promise<Search> {
	pagefind ??= importPagefind();
	return pagefind;
}

/** `/next/admin/upgrade.html#install` → `/next/admin/upgrade#install`. Pagefind's URL already carries the base path. */
const route = (url: string): string => url.replace(/\.html(?=#|$)/u, "");
const text = (html: string): string => html.replaceAll(/<[^>]+>/gu, "");

async function searchPagefind(query: string) {
	const search = await loadPagefind();
	const pages = await search(query);
	return pages.flatMap((found) => {
		const url = route(found.url);
		return [
			{ id: url, url, type: "page" as const, content: found.title },
			...found.headings
				.filter((heading) => route(heading.url) !== url)
				.slice(0, 3)
				.map((heading) => ({
					id: route(heading.url),
					url: route(heading.url),
					type: "heading" as const,
					content: text(heading.title),
				})),
		];
	});
}

export default function PagefindSearchDialog(props: SharedProps) {
	const search = useSearch(searchPagefind);
	return (
		<SearchDialog {...search} {...props}>
			<SearchDialogOverlay />
			<SearchDialogContent>
				<SearchDialogHeader>
					<SearchDialogIcon />
					<SearchDialogInput />
					<SearchDialogClose />
				</SearchDialogHeader>
				<SearchDialogList />
			</SearchDialogContent>
		</SearchDialog>
	);
}
