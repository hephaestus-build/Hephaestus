import { connect } from "node:net";

// Exits 0 once the peer's listener accepts on its own loopback, within five seconds. Argument: the port.
const port = Number(process.argv[1]);
const until = Date.now() + 5000;
const attempt = () => {
	const socket = connect(port, "127.0.0.1");
	socket.on("connect", () => {
		process.exit(0);
	});
	socket.on("error", () => {
		if (Date.now() < until) {
			setTimeout(attempt, 100);
		} else {
			process.exit(1);
		}
	});
};
attempt();
