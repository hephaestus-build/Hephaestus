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
import { Block, type BlockProps, Streamdown } from "streamdown";

import styles from "./UntrustedMarkdownRenderer.module.css";

import { InlineLink } from "@/components/common/InlineLink";
import { MarkdownCode } from "@/components/common/MarkdownCode";
import { linkIsAllowed, ModelLinkHostsContext } from "@/components/common/model-links";
import type {
	UntrustedMarkdownImage,
	UntrustedMarkdownProps,
} from "@/components/common/UntrustedMarkdown";

const HTTP_URL = /^https?:\/\//iu;

const plainText = (value: string): ReactNode => value;

/** The hosts the link rule allows for this text, or `null` for authored text, which keeps its links. */
const LinkRuleContext = createContext<readonly string[] | null>([]);

/** Carries {@link UntrustedMarkdownProps.renderText} to the text runs; the default is the words. */
const RenderTextContext = createContext(plainText);

/**
 * A link the model wrote is only a link when it is one: anything else renders as its own text.
 * A web address on a host outside {@link ModelLinkHostsContext} shows its words and the address as
 * code, so the reader sees where it points but cannot follow it by a click (OWASP LLM05).
 * A link opens in a new tab, so it carries the outbound icon and says so to a screen reader.
 */
function SafeAnchor({ href, children, className }: AnchorHTMLAttributes<HTMLAnchorElement>) {
	const hosts = useContext(LinkRuleContext);
	if (typeof href !== "string" || !HTTP_URL.test(href)) {
		return <span className={className}>{children}</span>;
	}
	if (hosts !== null && !linkIsAllowed(href, hosts)) {
		// A bare address renders its own words; `www.` ones gain a scheme in the link only.
		return typeof children === "string" && href.endsWith(children) ? (
			<code>{children}</code>
		) : (
			<span className={className}>
				{children} (<code>{href}</code>)
			</span>
		);
	}
	return (
		<InlineLink href={href} external>
			{/* A link's own words are left as written: a rewrite that links them would nest anchors. */}
			<RenderTextContext value={plainText}>{children}</RenderTextContext>
		</InlineLink>
	);
}

/** Carries {@link UntrustedMarkdownProps.headingLevel} to the headings. */
const HeadingLevelContext = createContext<3 | 4 | 5>(4);

/**
 * Every heading in the body takes one level, so a composed message cannot outrank the page heading
 * or the heading of the section it sits under. The visual size comes from the prose classes below.
 */
function DemotedHeading({ children, className }: HTMLAttributes<HTMLHeadingElement>) {
	const Heading = `h${useContext(HeadingLevelContext)}` as const;
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

/** One block of a streaming answer, in a wrapper that keeps its element while the block changes. */
function FadingBlock(props: BlockProps) {
	return (
		<div className={styles.newBlock}>
			<Block {...props} />
		</div>
	);
}

/** The renderer behind {@link UntrustedMarkdown}, which loads it on first use. */
export function UntrustedMarkdownRenderer({
	children,
	renderText,
	renderImage,
	streaming = false,
	headingLevel = 4,
	authored = false,
}: UntrustedMarkdownProps) {
	const linkHosts = useContext(ModelLinkHostsContext);
	return (
		<LinkRuleContext value={authored ? null : linkHosts}>
			<HeadingLevelContext value={headingLevel}>
				<RenderTextContext value={renderText ?? plainText}>
					<RenderImageContext value={renderImage ?? noImage}>
						<Streamdown
							mode={streaming ? "streaming" : "static"}
							isAnimating={streaming}
							// No span per word: styling each one costs a frame per chunk on a long answer.
							animated={false}
							BlockComponent={streaming ? FadingBlock : Block}
							rehypePlugins={[]}
							remarkRehypeOptions={{ allowDangerousHtml: false }}
							components={renderText ? RENDERED_TEXT_COMPONENTS : UNTRUSTED_MARKDOWN_COMPONENTS}
							translations={TRANSLATIONS}
						>
							{children}
						</Streamdown>
					</RenderImageContext>
				</RenderTextContext>
			</HeadingLevelContext>
		</LinkRuleContext>
	);
}
