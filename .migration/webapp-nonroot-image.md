#### 🔴 Send webapp traffic to port 8080

The webapp container now runs nginx as UID 101 and GID 101, and listens on port 8080 instead of port 80.
It needs no added capabilities and writes only to `/tmp/hephaestus-webapp`.
The bundled Compose stacks already route to port 8080, including the maintenance page.
They run the container with a read-only root file system and a `tmpfs` at `/tmp/hephaestus-webapp`.

**Operators:** do these steps only if you run the webapp image outside the bundled Compose stacks.

1. Change your reverse proxy, load balancer and health check to send traffic to container port 8080.
   For `docker run`, change `-p <host port>:80` to `-p <host port>:8080`.
   If you serve your own nginx configuration from this image, change its `listen` port to 8080.
2. Mount an empty directory at `/tmp/hephaestus-webapp` that UID 101 can write.
   With Docker, use `--tmpfs /tmp/hephaestus-webapp:uid=101,gid=101,mode=0700,size=16m`.
   Then you can make the root file system read-only.
3. Remove the `CHOWN`, `SETGID` and `SETUID` capabilities if you added them for nginx.
   Remove a `--user 0` or `user: root` override.
4. Make your legal override files readable by UID 101.
   The mount path `/usr/share/nginx/html/legal-overrides` does not change.

See [Webapp container](https://docs.hephaestus.build/admin/reference-deployment#webapp-container).
