import { useFumadocsLoader } from "fumadocs-core/source/client";
import type { TOCItemType } from "fumadocs-core/toc";
import { DocsLayout } from "fumadocs-ui/layouts/docs";
import {
	DocsBody,
	DocsDescription,
	DocsPage,
	DocsTitle,
	EditOnGitHub,
	MarkdownCopyButton,
	PageLastUpdate,
	ViewOptionsPopover,
} from "fumadocs-ui/layouts/docs/page";

import tree from "virtual:docs-page-tree";
import { Provider, type ProviderProps } from "@/components/provider";
import { baseOptions } from "@/lib/layout";

export interface DocsProps extends ProviderProps {
	title: string;
	description?: string;
	toc: TOCItemType[];
	/** The page's Markdown twin, with the base path. */
	markdownUrl: string;
	/** The page's published URL. */
	pageUrl: string;
	sourceUrl: string;
	editUrl: string;
	lastModified?: string;
}

/** The React island of a docs page. The content is static HTML, passed as children. */
export function Docs({
	title,
	description,
	toc,
	markdownUrl,
	pageUrl,
	sourceUrl,
	editUrl,
	lastModified,
	children,
	...props
}: DocsProps) {
	const data = useFumadocsLoader({ tree });
	return (
		<Provider {...props}>
			<DocsLayout {...baseOptions({ guides: false })} tree={data.tree}>
				<DocsPage toc={toc} tableOfContent={{ style: "clerk" }} data-pagefind-body="">
					<DocsTitle>{title}</DocsTitle>
					<DocsDescription>{description}</DocsDescription>
					<div className="border-fd-border -mt-4 flex flex-row items-center gap-2 border-b pb-6">
						<MarkdownCopyButton markdownUrl={markdownUrl} />
						<ViewOptionsPopover markdownUrl={markdownUrl} githubUrl={sourceUrl} pageUrl={pageUrl} />
					</div>
					<DocsBody>{children}</DocsBody>
					<div className="text-fd-muted-foreground flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
						<EditOnGitHub href={editUrl} />
						<a className="underline" href={markdownUrl}>
							View as Markdown
						</a>
						{lastModified === undefined ? null : <PageLastUpdate date={new Date(lastModified)} />}
					</div>
				</DocsPage>
			</DocsLayout>
		</Provider>
	);
}
