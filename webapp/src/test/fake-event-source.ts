/**
 * An `EventSource` a test drives by hand (installed by `setup-msw.ts`). A stream nobody drives stays
 * connecting and silent. Extends `EventTarget`, so listener bookkeeping — including `{ signal }`
 * removal — is the platform's.
 */
export class FakeEventSource extends EventTarget {
	static readonly CONNECTING = 0;
	static readonly OPEN = 1;
	static readonly CLOSED = 2;
	static instances: FakeEventSource[] = [];

	readyState = FakeEventSource.CONNECTING;
	closed = false;
	readonly url: string;
	readonly withCredentials: boolean;

	constructor(url: string | URL, options?: EventSourceInit) {
		super();
		this.url = String(url);
		this.withCredentials = options?.withCredentials ?? false;
		FakeEventSource.instances.push(this);
	}

	static latest(): FakeEventSource | undefined {
		return [...FakeEventSource.instances].reverse().find((source) => !source.closed);
	}

	close() {
		this.closed = true;
		this.readyState = FakeEventSource.CLOSED;
	}

	open() {
		this.readyState = FakeEventSource.OPEN;
		this.dispatchEvent(new Event("open"));
	}

	/** One named event, with `data` as the server writes it: JSON text. */
	emit(type: string, data: unknown) {
		this.emitRaw(type, JSON.stringify(data));
	}

	/** One named event with a payload that may not be JSON at all. */
	emitRaw(type: string, data: string) {
		this.dispatchEvent(new MessageEvent(type, { data }));
	}

	/** The spec's "reestablish the connection": a network error, which the browser retries itself. */
	error() {
		this.readyState = FakeEventSource.CONNECTING;
		this.dispatchEvent(new Event("error"));
	}

	/** The spec's "fail the connection": any non-200. The browser does not retry it. */
	fail() {
		this.readyState = FakeEventSource.CLOSED;
		this.dispatchEvent(new Event("error"));
	}
}
