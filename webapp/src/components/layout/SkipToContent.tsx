/**
 * Parked above the viewport until it has focus. Not `sr-only focus:not-sr-only`: that pair resets
 * `position`, so the focused link drops into the page's flow behind the fixed sidebar, where a
 * keyboard user presses Tab and sees nothing (WCAG 2.2 SC 2.4.1, 2.4.7, 2.4.11).
 */
export function SkipToContent() {
	return (
		<a
			href="#main-content"
			className="fixed top-4 left-4 z-[100] -translate-y-24 rounded-md bg-background px-4 py-2 text-sm font-medium text-foreground shadow-lg ring-2 ring-ring focus:translate-y-0"
			onClick={(event) => {
				const target = event.currentTarget.ownerDocument.getElementById(
					event.currentTarget.hash.slice(1),
				);
				if (!target) {
					return;
				}
				event.preventDefault();
				target.focus();
			}}
		>
			Skip to main content
		</a>
	);
}
