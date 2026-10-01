---
"hephaestus": minor
---

The application server no longer holds the Docker socket. AI sandboxes, for practice reviews and Heph alike, run only on the worker container, and a sandbox without internet access can reach that worker's sandbox gateway and nothing else: not the application server, the database, the message broker or the internet. Each release now checks this on the supported install before it is published.

**Operators:** The single-host install now runs the `application-worker` container as well. Run `./setup.sh` again before upgrading: it adds the worker registration token to `.env` and leaves your other values alone. The application server no longer joins the Docker group; only the worker needs `DOCKER_GROUP_ID`. Plan memory for three Java containers rather than two (5 GB, 3 GB and 2 GB by default), and set `SANDBOX_MAX_CONCURRENT` for the worker if you had raised it. `SANDBOX_DOCKER_APP_SERVER_CONTAINER_ID` is gone: each worker joins its sandboxes' networks as itself, so remove it from `.env`.
