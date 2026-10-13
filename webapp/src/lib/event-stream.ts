/** Backoff for manual reconnects: doubles from the base up to the cap, each step with jitter. */
const RECONNECT_BASE_MS = 1000;
const RECONNECT_CAP_MS = 30_000;

/**
 * Consecutive errors, the browser's own retries included, before the stream reports itself paused.
 * One is a blip — a proxy restart reconnects within a second — so tolerate it silently.
 */
const FAILURES_BEFORE_PAUSED = 2;

export interface EventStreamHandlers {
	/** Listeners for the stream's named events. */
	events: Record<string, (event: MessageEvent<string>) => void>;
	/** Runs on every successful connect, the first one included. */
	onOpen?: () => void;
	/** `true` after repeated failed connects, `false` again on the next successful one. */
	onPausedChange: (paused: boolean) => void;
}

/**
 * Opens a server-sent event stream that reconnects itself after the browser gives up on it.
 * Returns the function that closes it for good.
 */
export function openEventStream(url: string, handlers: EventStreamHandlers): () => void {
	let source: EventSource | undefined;
	let controller: AbortController | undefined;
	let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
	let failures = 0;
	let disposed = false;

	const detach = () => {
		controller?.abort();
		source?.close();
	};

	const connect = () => {
		const current = new EventSource(url, { withCredentials: true });
		// One controller per connection, not one per stream: an `AbortSignal` is one-shot, and
		// `addEventListener(…, { signal })` with an already-aborted signal silently attaches nothing.
		// Reusing a controller across reconnects leaves every stream after the first deaf.
		const currentController = new AbortController();
		source = current;
		controller = currentController;
		const { signal } = currentController;

		current.addEventListener(
			"open",
			() => {
				failures = 0;
				handlers.onPausedChange(false);
				handlers.onOpen?.();
			},
			{ signal },
		);
		for (const [type, listener] of Object.entries(handlers.events)) {
			current.addEventListener(type, listener, { signal });
		}
		current.addEventListener(
			"error",
			() => {
				failures += 1;
				if (failures >= FAILURES_BEFORE_PAUSED) {
					handlers.onPausedChange(true);
				}
				// CONNECTING means the browser is already retrying a network error itself (the HTML spec
				// makes that automatic), and fires this again for each retry that fails, so an outage
				// still counts. CLOSED means the connection failed — the spec reaches it for any non-200
				// or wrong Content-Type — and the browser never retries that: one 502 during a deploy or
				// one 401 on an expired session would end live updates.
				if (current.readyState !== EventSource.CLOSED) {
					return;
				}
				detach();
				if (disposed) {
					return;
				}
				const backoff = Math.min(RECONNECT_CAP_MS, RECONNECT_BASE_MS * 2 ** (failures - 1));
				// Jitter keeps every tab from re-storming the server on the same tick after a shared outage.
				reconnectTimer = setTimeout(connect, backoff * (0.5 + Math.random() * 0.5));
			},
			{ signal },
		);
	};

	connect();

	return () => {
		disposed = true;
		clearTimeout(reconnectTimer);
		detach();
	};
}
