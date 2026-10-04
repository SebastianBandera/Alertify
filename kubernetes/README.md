# Local Kubernetes deployment

Run `run.bat --deploy-kubernetes --non-interactive` (or `./run.sh` on Unix).
Kubernetes mode requires local Node.js 18+, Docker and kubectl. Compose remains
the default. Use `--kubeconfig=PATH` and `--kube-context=NAME` to select a cluster.
When launched without deployment options from a terminal, `run.bat` and `run.sh`
first offer Compose (the default), complete Kubernetes deployment, or Kubernetes
configuration validation. Kubernetes selections run on the host before Compose
port checks or bootstrap-container startup. Deployment still displays the target
context and asks for confirmation before applying resources. Compose continues
with its existing component checklist.
`--configure-only --deploy-kubernetes` validates configuration and client manifests
without building images or applying resources. Skip and Docker cleanup options
are rejected in Kubernetes mode.

The `.yaml.template` files use JSON syntax, which is valid YAML. Placeholders are
complete JSON values, escaped by the renderer. Resource configuration is derived
from Compose so the same URLs, runtime environment and build arguments are used.
Templates cover namespace, ConfigMaps, per-service Secrets, PostgreSQL PVCs and
StatefulSets, Services and Deployments for Keycloak, Redis, backend, frontend,
publisher and both worker types. The worker Service is headless so discovery
returns individual worker addresses. Private CA Secrets are never mounted into
application pods; only their respective leaf Secrets are mounted.
The publisher template uses the discovered cluster DNS Service instead of Docker
DNS and a fully qualified backend Service name; `KUBERNETES_CLUSTER_DOMAIN`
defaults to `cluster.local`. Nginx's dynamic resolver does not apply namespace
search suffixes to bare Service names. Mounted Keycloak initialization templates retain contextual redirect URIs
and use the path-free public origin for CORS. Mounted configuration is included
in pod configuration hashes so changing it triggers a rollout.

Set `KUBERNETES_NAMESPACE` in `.env`. Preferred node affinity uses existing
`alertify-role` labels: `infra`, `application`, `worker-standard`, and
`worker-playwright`. Missing roles produce warnings and allow alternate placement.
The runner does not change node labels.

Without a registry, images use `IfNotPresent`. Local kind clusters receive all images
through each schedulable node's containerd runtime, allowing affinity fallback.
Other clusters must share the Docker image
store or have those images preloaded. With `KUBERNETES_REGISTRY`, image names are
prefixed with the registry; `KUBERNETES_PUSH_IMAGES=true` pushes them after building.
Registry images use `Always` to retrieve updated tags. The Docker image digest is
included in pod annotations so rebuilding changed sources triggers a new rollout.
Kind imports use platform-specific Docker exports (Docker 28+ APIs) and compare
actual node manifest digests before transferring images again.
Configure `KUBERNETES_STORAGE_CLASS` or leave it empty for the default storage class.
Database PVCs and applied resources survive subsequent runner calls.

The runner displays the target and asks before applying unless
`--non-interactive` is passed. It validates client and server manifests, applies
without prune, and waits for rollout and pod readiness. Rendered manifests and
certificate copies live only in a uniquely named OS temporary directory removed
in `finally`. Existing certificate Secrets are reused. The public publisher CA
is exported to `.alertify/certificates/alertify-local-ca.crt` for local trust.

`KUBERNETES_PORT_FORWARD=true` maintains the configured local public ports using a
detached kubectl supervisor. Logs and helper state are ignored under
`.alertify/kubernetes`. The helper retries after pod or connection loss and keeps
running when Git branches change. Configure false when an ingress/load balancer
already publishes the publisher Service. The host ports must be free; stop any
Compose publisher using them before Kubernetes deployment. For permanent remote
deployment, use an ingress or load balancer managed for that environment.

With local forwarding, the runner verifies the public API over the configured
hostname and its explicit CA before reporting success. The anonymous request must
return 401 or 403, demonstrating that routing reaches the protected backend.
