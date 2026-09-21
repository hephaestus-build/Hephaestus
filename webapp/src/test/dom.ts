export function precedes(earlier: Node, later: Node): boolean {
	// oxlint-disable-next-line no-bitwise -- compareDocumentPosition returns a bitmask
	return (earlier.compareDocumentPosition(later) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
}
