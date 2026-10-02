import markSvg from "@/brand/hephaestus-mark.svg?raw";

/**
 * The practice review report's box in the provider's page, as plain DOM with no extension API, so the
 * content script and the tests build the same thing. It holds the generic loading line and the
 * extension-origin frame, and nothing else: whatever the report says about the work or the reader
 * exists only inside that frame, which the page cannot read.
 */

export const LOADING_TEXT = "Loading…";
export const FAILED_TEXT = "Could not load here. Reload the page to try again.";

// The canonical Heph mark, bundled; the text beside it names it, so it is decorative.
const MARK = `<span class="mark" aria-hidden="true">${markSvg}</span>`;

export interface ReportFrame {
	iframe: HTMLIFrameElement;
	/** The generic loading or failure line; gone once the frame is ready. */
	status: HTMLElement;
	/** The frame did not become ready in time: say so, generically. */
	fail: () => void;
	/** The frame is ready: show it instead of the line. */
	reveal: () => void;
}

/**
 * Fills the report's box with the generic line and the frame, hidden until the frame says it is ready:
 * an empty frame must not pass for an answer. The line says nothing about the work or the account.
 */
export function renderReport(container: HTMLElement, source: string): ReportFrame {
	const doc = container.ownerDocument;
	const box = doc.createElement("div");
	box.className = "report";

	const status = doc.createElement("div");
	status.className = "bar";
	status.setAttribute("role", "status");
	status.innerHTML = `${MARK}<span class="name">Practice review</span>`;
	const line = doc.createElement("p");
	line.textContent = LOADING_TEXT;
	status.append(line);

	// The global document creates it wherever the box is; appending adopts it. A sandbox would take
	// the extension page's own origin away from it, which its messages are checked against.
	const iframe = document.createElement("iframe");
	iframe.className = "frame";
	iframe.title = "Hephaestus practice review";
	iframe.hidden = true;
	iframe.src = source;

	box.append(status, iframe);
	container.replaceChildren(box);
	return {
		iframe,
		status,
		fail: () => {
			line.textContent = FAILED_TEXT;
			line.title = FAILED_TEXT;
		},
		reveal: () => {
			status.remove();
			iframe.hidden = false;
		},
	};
}
