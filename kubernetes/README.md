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
publisher and both worker types, plus Traefik, its RBAC, IngressClass and Ingress.
The worker Service is headless so discovery
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

Publication is handled by Traefik and a standard Kubernetes Ingress. The
controller watches only the application namespace. Its Service defaults to
`LoadBalancer`, exposing `PUBLIC_HTTP_PORT` and `PUBLIC_PORT`; Docker Desktop's
load balancer integration publishes these ports on the local machine. Other
clusters require their own load balancer integration or an explicitly exposed
NodePort. Merely creating an Ingress does not expose host ports.
Host listening addresses are determined by the load balancer provider, not by
Compose's `BIND_ADDRESS`. Docker Desktop binds its LoadBalancer ports on all
host interfaces; the hostname rule still selects the application.

The Ingress matches the hostname from `APP_PUBLIC_URL` and forwards `/` to the
publisher without rewriting paths. The publisher retains the root redirect,
application context, Keycloak routing and WebSocket handling. For HTTPS,
Traefik redirects HTTP to the configured public HTTPS port and presents a TLS
Secret reconciled from the existing publisher leaf certificate. It also uses
HTTPS to reach the publisher, verifying the public CA and configured hostname;
certificate verification is never disabled. The controller mounts only a
separate public CA Secret, never the private CA Secret.

Configure publication in `.env`:

| Variable | Default | Purpose |
| --- | --- | --- |
| `KUBERNETES_INGRESS_ENABLED` | `true` | Deploy the managed Traefik controller and Ingress. |
| `KUBERNETES_INGRESS_CLASS` | `alertify-traefik` | Class owned by this deployment. Use a distinct class for another installation. |
| `KUBERNETES_TRAEFIK_IMAGE` | `traefik:v3.7.13` | Pinned controller image. |
| `KUBERNETES_TRAEFIK_SERVICE_TYPE` | `LoadBalancer` | `LoadBalancer`, `NodePort` or `ClusterIP`, depending on external publication. |
| `KUBERNETES_VERIFY_LOCAL_PUBLIC_URL` | `true` | Verify the public API through loopback with the exported CA. Disable for an entry point outside this PC. |

Disabling managed Ingress does not delete previously applied resources. When
moving to a separate controller, reconcile that controller and the old Ingress
explicitly. An IngressClass owned by another controller is never overwritten.

The deployment no longer starts any `kubectl port-forward` process. On upgrade,
it stops its old publisher supervisor through the retained unique stop marker,
after pod readiness, without killing a stored PID. Historical logs remain under
the ignored `.alertify/kubernetes` directory. Public ports must be available;
stop any Compose publisher using them before deployment. Docker Desktop and
Kubernetes must remain running for local publication.

With local public URL verification enabled, the runner waits for the anonymous
API to return 401 or 403 over trusted TLS before reporting success. Verify fresh
frontend login and WebSocket traffic when changing publication settings.
