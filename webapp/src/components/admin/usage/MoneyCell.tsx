/**
 * Pads a money figure so its decimal point lands where every other row's does — `tabular-nums`
 * equalises glyph *width* but not a missing `.00`. `visibility: hidden` keeps the space that
 * `display: none` would collapse, and `aria-hidden` keeps the pad out of the accessible name.
 */
export function MoneyCell({ children }: { children: string }) {
	return (
		<>
			{children}
			{!children.includes(".") && (
				<span className="invisible" aria-hidden>
					.00
				</span>
			)}
		</>
	);
}
