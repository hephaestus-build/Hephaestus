import { isValidElement, type JSX, type ReactNode } from "react";
import {
	CodeBlock,
	CodeBlockCopyButton,
	CodeBlockDownloadButton,
	type ExtraProps,
	useIsCodeFenceIncomplete,
} from "streamdown";

import { cn } from "cn";

type MarkdownCodeProps = JSX.IntrinsicElements["code"] &
	ExtraProps & {
		"data-block"?: string;
	};

const LANGUAGE_PATTERN = /language-([^\s]+)/;
const START_LINE_PATTERN = /startLine=(\d+)/;
const NO_LINE_NUMBERS_PATTERN = /\bnoLineNumbers\b/;

function codeText(children: ReactNode): string {
	if (typeof children === "string") return children;
	if (
		isValidElement<{ children?: ReactNode }>(children) &&
		typeof children.props.children === "string"
	) {
		return children.props.children;
	}
	return "";
}

export function MarkdownCode({
	node,
	className,
	children,
	"data-block": block,
	...props
}: MarkdownCodeProps) {
	const isIncomplete = useIsCodeFenceIncomplete();

	if (block === undefined) {
		return (
			<code
				className={cn("rounded bg-muted px-1.5 py-0.5 font-mono text-sm", className)}
				{...props}
			>
				{children}
			</code>
		);
	}

	const code = codeText(children);
	const language = className?.match(LANGUAGE_PATTERN)?.[1] ?? "";
	const meta =
		typeof node?.properties.metastring === "string" ? node.properties.metastring : undefined;
	const startLineDigits = meta?.match(START_LINE_PATTERN)?.[1];
	const parsedStartLine =
		startLineDigits === undefined ? undefined : Number.parseInt(startLineDigits, 10);
	const startLine = parsedStartLine && parsedStartLine >= 1 ? parsedStartLine : undefined;
	const lineNumbers = !meta || !NO_LINE_NUMBERS_PATTERN.test(meta);

	return (
		<CodeBlock
			className={cn("[&_pre]:text-foreground", className)}
			code={code}
			isIncomplete={isIncomplete}
			language={language}
			lineNumbers={lineNumbers}
			startLine={startLine}
			tabIndex={0}
		>
			<CodeBlockDownloadButton code={code} language={language} />
			<CodeBlockCopyButton />
		</CodeBlock>
	);
}
