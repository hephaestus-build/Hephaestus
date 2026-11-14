import { createServer } from "node:net";

// Starts a listener that outlives the exec, so the peer has something to reach. Argument: the port.
createServer((socket) => {
	socket.end();
}).listen(Number(process.argv[1]));
setTimeout(() => {
	process.exit(0);
}, 120_000);
