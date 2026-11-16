import defaultMdxComponents from "fumadocs-ui/mdx";
import type { MDXComponents } from "mdx/types";
import type { AnchorHTMLAttributes, ComponentPropsWithoutRef } from "react";

import { CalloutContainer, CalloutDescription, CalloutTitle } from "@/components/callout";
import { Screenshot } from "@/components/screenshot";
import { SystemDiagram } from "@/components/system-diagram";
import { BASE } from "@/lib/site";
import { type DocsPage, source } from "@/lib/source";

const ASSET = /\.(?:svg|webp|png|jpe?g|ya?ml|json|txt|pdf)$/u;
const base = BASE;

/*
 * The MDX components of a page. The content renders to static HTML with them; none of them hydrates.
 * A link to a page file (`./upgrade.mdx`) resolves to the page URL, which carries the base path. A URL-relative link
 * (`./upgrade`) resolves against the page URL, as the browser would; a root-relative link (`/admin/upgrade`) gets the
 * base path.
 */
type HeadingTag = "h1" | "h2" | "h3" | "h4" | "h5" | "h6";

/*
 * A heading links to its own anchor. Fumadocs' heading adds a copy button instead, but the content does not hydrate, so
 * that button does nothing, and its screen-reader text "Copy Anchor Link" enters each search result's heading.
 */
function anchoredHeading(Tag: HeadingTag) {
	return function DocsHeading({
		id,
		children,
		className,
		...props
	}: ComponentPropsWithoutRef<HeadingTag>) {
		if (id === undefined) {
			return (
				<Tag className={className} {...props}>
					{children}
				</Tag>
			);
		}
		return (
			<Tag id={id} className={`scroll-m-28 ${className ?? ""}`.trim()} {...props}>
				<a data-card="" href={`#${id}`}>
					{children}
				</a>
			</Tag>
		);
	};
}

export function mdxComponents(page: DocsPage) {
	function DocsAnchor({ href = "", children, ...props }: AnchorHTMLAttributes<HTMLAnchorElement>) {
		if (/^(?:[a-z]+:|#|\/\/)/u.test(href)) {
			return (
				<a href={href} {...props}>
					{children}
				</a>
			);
		}
		const resolved = source.resolveHref(href, page);
		const absolute =
			resolved.startsWith("/") && !resolved.startsWith(base)
				? `${base}${resolved.slice(1)}`
				: resolved;
		const url = new URL(absolute, `https://docs.invalid${page.url}`);
		const path = ASSET.test(url.pathname) ? url.pathname : url.pathname.replace(/\.mdx?$/u, "");
		return (
			<a href={`${path}${url.hash}`} {...props}>
				{children}
			</a>
		);
	}
	return {
		...defaultMdxComponents,
		a: DocsAnchor,
		h1: anchoredHeading("h1"),
		h2: anchoredHeading("h2"),
		h3: anchoredHeading("h3"),
		h4: anchoredHeading("h4"),
		h5: anchoredHeading("h5"),
		h6: anchoredHeading("h6"),
		CalloutContainer,
		CalloutTitle,
		CalloutDescription,
		Screenshot,
		SystemDiagram,
	} satisfies MDXComponents;
}
