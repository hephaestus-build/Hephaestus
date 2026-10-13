import {
	type AnchorHTMLAttributes,
	createContext,
	Fragment,
	type HTMLAttributes,
	type ImgHTMLAttributes,
	type InputHTMLAttributes,
	type OlHTMLAttributes,
	type ReactNode,
	useContext,
} from "react";
import { Streamdown } from "streamdown";

import { MarkdownCode } from "@/components/common/MarkdownCode";

const HTTP_URL = /^https?:\/\//iu;

const plainText = (value: string): ReactNode => value;

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

/** Carries {@link UntrustedMarkdownProps.headingLevel} to the headings. */
const HeadingLevelContext = createContext<3 | 4>(4);

/**
 * Every heading in the body takes one level, so a composed message cannot outrank the page heading
 * or the heading of the section it sits under. The visual size comes from the prose classes below.
 */
function DemotedHeading({ children, className }: HTMLAttributes<HTMLHeadingElement>) {
	const Heading = useContext(HeadingLevelContext) === 3 ? "h3" : "h4";
	return <Heading className={className}>{children}</Heading>;
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

/** An image in the Markdown, as {@link UntrustedMarkdownProps.renderImage} receives it. */
export interface UntrustedMarkdownImage {
	src: string;
	alt: string;
}

const noImage = (): ReactNode => null;

/** Carries {@link UntrustedMarkdownProps.renderImage} to the images; the default draws none. */
const RenderImageContext = createContext<(image: UntrustedMarkdownImage) => ReactNode>(noImage);

/** No image loads unless the caller draws it, so a remote image cannot report who read the text. */
function ChosenImage({ src, alt }: ImgHTMLAttributes<HTMLImageElement>): ReactNode {
	const renderImage = useContext(RenderImageContext);
	return typeof src === "string" ? renderImage({ src, alt: alt ?? "" }) : null;
}

/**
 * Lists without the renderer's `list-inside`, so the prose styles place the marker outside and a
 * wrapped line lines up with the text rather than under the bullet.
 */
function UnorderedList({ children }: HTMLAttributes<HTMLUListElement>) {
	return <ul>{children}</ul>;
}

function OrderedList({ children, start }: OlHTMLAttributes<HTMLOListElement>) {
	return <ol start={start}>{children}</ol>;
}

/** A task list's checkbox: the renderer gives it no label, and its checked state is announced already. */
function TaskCheckbox(props: InputHTMLAttributes<HTMLInputElement>) {
	return <input {...props} aria-label="Task" />;
}

/** The library names its code-block button in title case; every other control here uses sentence case. */
const TRANSLATIONS = { copyCode: "Copy code" };

const UNTRUSTED_MARKDOWN_COMPONENTS = {
	a: SafeAnchor,
	ul: UnorderedList,
	ol: OrderedList,
	code: MarkdownCode,
	img: ChosenImage,
	input: TaskCheckbox,
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
/** Each word that arrives gets `data-sd-animate` and a `--sd-delay` that staggers it after the last. */
const NEW_WORDS = { animation: "fadeIn", sep: "word" } as const;

export const UNTRUSTED_MARKDOWN_PROSE =
	"prose prose-sm dark:prose-invert max-w-none break-words prose-headings:mt-4 prose-headings:mb-1.5 prose-headings:text-sm prose-headings:font-semibold prose-p:my-2 prose-ul:my-2 prose-ol:my-2 prose-li:my-0.5 prose-pre:my-2 prose-pre:overflow-x-visible prose-pre:whitespace-pre-wrap prose-pre:break-words prose-code:before:content-none prose-code:after:content-none first:prose-headings:mt-0";

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
	 * as an open `**`, renders as if it were closed, and each new word carries `data-sd-animate` for
	 * the caller to fade in.
	 */
	streaming?: boolean;
	/** The level of every heading in the body: one below the heading of the section it sits under. */
	headingLevel?: 3 | 4;
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
export function UntrustedMarkdown({
	children,
	renderText,
	renderImage,
	streaming = false,
	headingLevel = 4,
}: UntrustedMarkdownProps) {
	return (
		<HeadingLevelContext.Provider value={headingLevel}>
			<RenderTextContext.Provider value={renderText ?? plainText}>
				<RenderImageContext.Provider value={renderImage ?? noImage}>
					<Streamdown
						mode={streaming ? "streaming" : "static"}
						isAnimating={streaming}
						// Marks each new word while the text streams; the caller decides whether and how it fades.
						animated={streaming ? NEW_WORDS : false}
						rehypePlugins={[]}
						remarkRehypeOptions={{ allowDangerousHtml: false }}
						components={renderText ? RENDERED_TEXT_COMPONENTS : UNTRUSTED_MARKDOWN_COMPONENTS}
						translations={TRANSLATIONS}
					>
						{children}
					</Streamdown>
				</RenderImageContext.Provider>
			</RenderTextContext.Provider>
		</HeadingLevelContext.Provider>
	);
}
