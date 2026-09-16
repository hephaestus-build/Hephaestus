export interface FormattedTitleProps {
	title: string;
	className?: string;
}

/**
 * FormattedTitle component renders text with inline code segments.
 *
 * It parses text wrapped in backticks (`) and renders them as <code> elements,
 * making it easy to highlight code snippets within regular text.
 */
export function FormattedTitle({ title, className = "" }: FormattedTitleProps) {
	// Parse title with code highlighting by splitting on backticks
	// and converting the code segments into proper elements
	const titleParts = title
		.split(/(?<code>`[^`]+`)/u)
		.filter(Boolean)
		.map((part, index) => {
			// Create a stable key using the content and position
			const stableKey = `${part.slice(0, 10).replaceAll(/\s+/gu, "-")}-${index}`;

			if (part.startsWith("`") && part.endsWith("`")) {
				// This is a code segment (remove the backticks)
				const content = part.slice(1, -1);
				return (
					<code key={stableKey} className="textCode">
						{content}
					</code>
				);
			}

			// This is regular text
			return <span key={stableKey}>{part}</span>;
		});

	return <span className={className}>{titleParts}</span>;
}
