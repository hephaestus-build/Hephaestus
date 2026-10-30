/** SSE framing across arbitrary UTF-8 chunks; comments never become answer events. */
export class CapacityEvents {
	private readonly decoder = new TextDecoder();
	private buffered = "";
	private event = "message";
	private data: string[] = [];
	private eventChars = 0;

	push(chunk: Uint8Array, receive: (event: string, data: string) => void) {
		this.buffered += this.decoder.decode(chunk, { stream: true });
		if (this.buffered.length > 8 * 1024 * 1024) {
			throw new Error("SSE line exceeds benchmark bound");
		}
		let newline = this.buffered.indexOf("\n");
		while (newline >= 0) {
			const line = this.buffered.slice(0, newline).replace(/\r$/u, "");
			this.buffered = this.buffered.slice(newline + 1);
			if (line === "") {
				if (this.data.length > 0) {
					receive(this.event, this.data.join("\n"));
				}
				this.data = [];
				this.eventChars = 0;
				this.event = "message";
			} else if (line.startsWith("data:")) {
				this.eventChars += line.length;
				if (this.eventChars > 8 * 1024 * 1024) {
					throw new Error("SSE event exceeds benchmark bound");
				}
				this.data.push(line.slice(5).replace(/^ /u, ""));
			} else if (line.startsWith("event:")) {
				this.event = line.slice(6).replace(/^ /u, "");
			}
			newline = this.buffered.indexOf("\n");
		}
	}
}
