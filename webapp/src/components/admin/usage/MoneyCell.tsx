export interface MoneyCellProps {
	/** `null` where the cell has no figure, such as an average over nothing: a muted dash. */
	children: string | null;
}

/** One money figure in a usage table, or a muted dash where there is none to show. */
export function MoneyCell({ children }: MoneyCellProps) {
	if (children == null) {
		return <span className="text-muted-foreground">—</span>;
	}
	return children;
}
