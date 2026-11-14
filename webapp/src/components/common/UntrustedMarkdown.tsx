import { CatchBoundary } from "@tanstack/react-router";
import { lazy, type ReactNode, Suspense } from "react";
import { useSpinDelay } from "spin-delay";

import { Skeleton } from "@/components/ui/skeleton";

/**
 * The Markdown stack (the parser, the HTML tree tools and the renderer) is the largest library on
 * the pages that show feedback. A page fetches it the first time it shows Markdown, not with its
 * own code.
 */
const UntrustedMarkdownRenderer = lazy(async () => {
	const renderer = await import("@/components/common/UntrustedMarkdownRenderer");
	return { default: renderer.UntrustedMarkdownRenderer };
});

/**
 * Two departures from Tailwind Typography's defaults, which are sized for an article rather than a
 * short composed message.
 *
 * <p>The heading rhythm is tightened, because `mt-8` above a heading assumes a section the reader
 * scrolled to rather than one of three inside a few hundred words.
 *
 * <p>`pre` gives up `overflow-x: auto` and wraps instead. A composer quoting code in a fenced block
 * wider than its container would otherwise make a scrollable region no keyboard can reach — axe's
 * `scrollable-region-focusable`, and a real problem on a phone, where the end of the line needs a
 * horizontal drag inside a vertically scrolling page.
 */
export const UNTRUSTED_MARKDOWN_PROSE =
	"prose prose-sm dark:prose-invert max-w-none break-words prose-headings:mt-4 prose-headings:mb-1.5 prose-headings:text-sm prose-headings:font-semibold prose-p:my-2 prose-ul:my-2 prose-ol:my-2 prose-li:my-0.5 prose-pre:my-2 prose-pre:overflow-x-visible prose-pre:whitespace-pre-wrap prose-pre:break-words prose-code:before:content-none prose-code:after:content-none first:prose-headings:mt-0";

/** An image in the Markdown, as {@link UntrustedMarkdownProps.renderImage} receives it. */
export interface UntrustedMarkdownImage {
	src: string;
	alt: string;
}

export interface UntrustedMarkdownProps {
	children: string;
	/**
	 * Rewrites every run of plain text: each paragraph, list item, bold and italic run outside a
	 * link. What a backtick or a fence holds never goes through it — code is quoted, not prose.
	 */
	renderText?: (value: string) => ReactNode;
	/**
	 * Draws an image the caller vouches for, by its source as written; anything it returns `null`
	 * for draws nothing. Without it no image renders at all.
	 */
	renderImage?: (image: UntrustedMarkdownImage) => ReactNode;
	/**
	 * The words are still arriving, each version longer than the last. An unfinished construct, such
	 * as an open `**`, renders as if it were closed, and each block (a paragraph, a list, a code
	 * block) fades in once, when it is appended.
	 */
	streaming?: boolean;
	/** The level of every heading in the body: one below the heading of the section it sits under. */
	headingLevel?: 3 | 4 | 5;
	/**
	 * A person wrote the text, such as a practice guide, so its web links stay links. Model-written
	 * text leaves it unset and links only to the hosts of `ModelLinkHostsContext`.
	 */
	authored?: boolean;
}

/**
 * What stands in while the renderer loads: nothing for the first second, so a fast load does not
 * flash a placeholder, then a skeleton.
 */
export function UntrustedMarkdownLoading() {
	const visible = useSpinDelay(true, { delay: 1000, minDuration: 0 });
	return visible ? (
		<div aria-busy="true" className="flex flex-col gap-2">
			<Skeleton className="h-4 w-3/4" />
			<Skeleton className="h-4 w-full" />
			<Skeleton className="h-4 w-1/2" />
		</div>
	) : null;
}

/**
 * Markdown a model wrote, rendered with no HTML passthrough, no image the caller does not draw
 * itself and no link the renderer has not checked. Shared rather than copied, so the safety
 * decisions are made once: the operator's feedback preview and the developer's own practice pages
 * show the same text, and a hardening applied to one must not be able to miss the other.
 *
 * <p>Brings no wrapper of its own. Callers put {@link UNTRUSTED_MARKDOWN_PROSE} on whichever element
 * they already have, so the prose scope cannot end up nested inside itself.
 */
export function UntrustedMarkdown(props: UntrustedMarkdownProps) {
	return (
		<LazyMarkdown source={props.children}>
			<UntrustedMarkdownRenderer {...props} />
		</LazyMarkdown>
	);
}

/**
 * Where a lazily loaded Markdown renderer mounts. While it loads, the delayed skeleton shows. If it
 * cannot load, for example because a deploy removed the chunk this tab asks for, the source shows as
 * plain text, so the words stay readable and nothing else on the page breaks. React keeps a failed
 * lazy load, so a retry could not help; the root's error handlers report the failure.
 */
export function LazyMarkdown({ source, children }: { source: string; children: ReactNode }) {
	return (
		<CatchBoundary
			getResetKey={() => source}
			errorComponent={() => <p className="break-words whitespace-pre-wrap">{source}</p>}
		>
			<Suspense fallback={<UntrustedMarkdownLoading />}>{children}</Suspense>
		</CatchBoundary>
	);
}
