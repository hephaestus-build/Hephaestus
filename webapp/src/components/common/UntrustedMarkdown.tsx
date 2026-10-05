import {
	type AnchorHTMLAttributes,
	createContext,
	Fragment,
	type HTMLAttributes,
	type ReactNode,
	useContext,
} from "react";
import { defaultRehypePlugins, Streamdown } from "streamdown";

import { MarkdownCode } from "@/components/common/MarkdownCode";

const HTTP_URL = /^https?:\/\//iu;

const plainText = (value: string): ReactNode => value;

/**
 * The HTML a body carries is parsed and then cut down to GitHub's own allowlist, never passed
 * through. A comment Hephaestus posted on a pull request is the composer's Markdown inside an
 * envelope of HTML: a `<!-- hephaestus:practice-review:… -->` marker and a `<sub>` footer. GitHub
 * renders the footer small and hides the marker, so a page showing that body must do the same.
 * Without the parse the renderer escapes each tag, and the reader gets the envelope as literal text.
 *
 * <p>Streamdown's own pair is reused rather than installed again: `rehype-raw` turns the HTML into
 * elements, and `rehype-sanitize` with GitHub's schema keeps only the tags and attributes GitHub
 * keeps. Its schema drops comments, scripts, styles, event handlers and any link that is not
 * http, https or mailto. Streamdown's third default, `harden`, is left out: here it is configured
 * to allow every prefix, and {@link SafeAnchor} below is the stricter link check.
 */
const GITHUB_HTML_PLUGINS = [defaultRehypePlugins.raw, defaultRehypePlugins.sanitize].filter(
	(plugin) => plugin !== undefined,
);

/** Carries {@link UntrustedMarkdownProps.renderText} to the text runs; the default is the words. */
const RenderTextContext = createContext(plainText);

/** A link the model wrote is only a link when it is one: anything else renders as its own text. */
function SafeAnchor({ href, children, className }: AnchorHTMLAttributes<HTMLAnchorElement>) {
	if (typeof href !== "string" || !HTTP_URL.test(href)) {
		return <span className={className}>{children}</span>;
	}
	return (
		<a href={href} className={className} rel="noopener noreferrer" target="_blank">
			{/* A link's own words are left as written: a rewrite that links them would nest anchors. */}
			<RenderTextContext.Provider value={plainText}>{children}</RenderTextContext.Provider>
		</a>
	);
}

/**
 * Every heading in the body becomes an `h4`, so a composed message cannot outrank the page heading
 * or the heading of the section it sits under. The visual size comes from the prose classes below.
 */
function DemotedHeading({ children, className }: HTMLAttributes<HTMLHeadingElement>) {
	return <h4 className={className}>{children}</h4>;
}

/**
 * The strings among an element's children as the caller writes them; everything else stands. The
 * renderer hands over a single node or an array of them, and only the strings are text runs — a
 * nested element carries its own, through its own `TextRuns`.
 */
function TextRuns({ children }: { children?: ReactNode }): ReactNode {
	const renderText = useContext(RenderTextContext);
	if (typeof children === "string") {
		return renderText(children);
	}
	if (!Array.isArray(children)) {
		return children;
	}
	return children.map((child: ReactNode, index): ReactNode =>
		typeof child === "string" ? <Fragment key={index}>{renderText(child)}</Fragment> : child,
	);
}

/**
 * GitHub's small print, such as the footer under a posted comment. It stays on the base line in
 * the muted tone, so it reads as a note under the text rather than as a lowered run of body text.
 */
function SmallPrint({ children }: HTMLAttributes<HTMLElement>) {
	return <small className="text-xs text-muted-foreground">{children}</small>;
}

const UNTRUSTED_MARKDOWN_COMPONENTS = {
	a: SafeAnchor,
	sub: SmallPrint,
	code: MarkdownCode,
	// A remote image is a request the reader never asked for; `picture` and `source` are its other
	// spelling in the allowlist.
	img: () => null,
	picture: () => null,
	source: () => null,
	h1: DemotedHeading,
	h2: DemotedHeading,
	h3: DemotedHeading,
	h4: DemotedHeading,
	h5: DemotedHeading,
	h6: DemotedHeading,
};

/**
 * The elements the Markdown puts prose in. Overriding one costs the renderer's own styling for it,
 * so they stand in only for a caller that rewrites the words — the rest keep what the renderer
 * gives them.
 */
const RENDERED_TEXT_COMPONENTS = {
	...UNTRUSTED_MARKDOWN_COMPONENTS,
	p: ({ children, className }: HTMLAttributes<HTMLParagraphElement>) => (
		<p className={className}>
			<TextRuns>{children}</TextRuns>
		</p>
	),
	li: ({ children, className }: HTMLAttributes<HTMLLIElement>) => (
		<li className={className}>
			<TextRuns>{children}</TextRuns>
		</li>
	),
	strong: ({ children, className }: HTMLAttributes<HTMLElement>) => (
		<strong className={className}>
			<TextRuns>{children}</TextRuns>
		</strong>
	),
	em: ({ children, className }: HTMLAttributes<HTMLElement>) => (
		<em className={className}>
			<TextRuns>{children}</TextRuns>
		</em>
	),
};

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
	"prose prose-sm dark:prose-invert max-w-none break-words prose-headings:mt-4 prose-headings:mb-1.5 prose-headings:text-sm prose-headings:font-semibold prose-p:my-2 prose-ul:my-2 prose-ol:my-2 prose-li:my-0.5 prose-pre:my-2 prose-pre:overflow-x-visible prose-pre:whitespace-pre-wrap prose-pre:break-words first:prose-headings:mt-0";

export interface UntrustedMarkdownProps {
	children: string;
	/**
	 * Rewrites every run of plain text: each paragraph, list item, bold and italic run outside a
	 * link. What a backtick or a fence holds never goes through it — code is quoted, not prose.
	 */
	renderText?: (value: string) => ReactNode;
}

/**
 * Markdown a model wrote, rendered with no HTML passthrough, no remote images and no link the
 * renderer has not checked. Inline HTML renders only as far as GitHub's allowlist reaches, so a
 * body posted on a pull request reads here as it reads there. Shared rather than copied, so the safety decisions are made once: the
 * operator's feedback preview and the developer's own practice pages show the same text, and a
 * hardening applied to one must not be able to miss the other.
 *
 * <p>Brings no wrapper of its own. Callers put {@link UNTRUSTED_MARKDOWN_PROSE} on whichever element
 * they already have, so the prose scope cannot end up nested inside itself.
 */
export function UntrustedMarkdown({ children, renderText }: UntrustedMarkdownProps) {
	const markdown = (
		<Streamdown
			mode="static"
			rehypePlugins={GITHUB_HTML_PLUGINS}
			// The HTML has to reach `rehype-raw` to be parsed at all; the sanitizer after it decides
			// what is left of it.
			remarkRehypeOptions={{ allowDangerousHtml: true }}
			components={renderText ? RENDERED_TEXT_COMPONENTS : UNTRUSTED_MARKDOWN_COMPONENTS}
		>
			{children}
		</Streamdown>
	);
	if (!renderText) {
		return markdown;
	}
	return <RenderTextContext.Provider value={renderText}>{markdown}</RenderTextContext.Provider>;
}
