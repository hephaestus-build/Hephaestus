import { useEffect } from "react";

/**
 * Walks a reader back to the last page that exists once the result count says the one they asked for
 * does not. A page number lives in the URL, so it outlives the result set that made it reachable, and
 * neither the search parser nor the render can prevent that — the total only arrives with the
 * response.
 *
 * It corrects by navigating rather than by rendering a page the URL does not name, so the address
 * stays shareable and the correction survives a reload.
 */
export function useClampedPage(
	page: number | undefined,
	totalPages: number | undefined,
	onPageChange: (page: number) => void,
) {
	useEffect(() => {
		if (totalPages !== undefined && page !== undefined && page >= totalPages) {
			onPageChange(Math.max(0, totalPages - 1));
		}
	}, [onPageChange, page, totalPages]);
}
