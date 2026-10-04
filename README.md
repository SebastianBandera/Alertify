# Alertify

Alertify is a monitoring and automation application. Create scheduled alerts,
follow their status in a live dashboard, and build reusable operations and
workflows with Procedures, Pipes, and Hooks.

## Quick start

Install and start Docker with Linux container support. Download or clone this
repository, then open a terminal in the project folder.

**Windows (PowerShell or Command Prompt):**

```powershell
.\run.bat
```

**Linux / macOS:**

```sh
sh ./run.sh
```

The launcher first asks where to deploy: Docker Compose (the default), Kubernetes,
or Kubernetes configuration validation. **For your first Compose run, press Enter
to choose Compose, then leave every checklist option unchecked and press Enter.**
This starts the complete application. Use the arrow keys to move and Space to
toggle a checklist option when you need a different setup.

Kubernetes selections require local Node.js 18+ and kubectl. The deployment option
shows the target cluster and asks for confirmation before applying resources;
configuration validation does not deploy. See
[Local Kubernetes deployment](kubernetes/README.md) for requirements and settings.

The first run downloads images and builds the application, so allow time for it
to finish. The launcher creates `.env`, generates the initial credentials, and
prepares the services and certificates it needs. You do not need to install Java,
Maven, or Node.js on your computer.

### Open Alertify

With the default configuration, open **[http://localhost/alertify](http://localhost/alertify)**.

Open the generated `.env` file locally and find:

- `APP_ADMIN_USERNAME`: the initial application administrator username.
- `APP_ADMIN_PASSWORD`: the generated password for that account.

Use those credentials to sign in. The `KEYCLOAK_ADMIN_*` credentials belong to
identity-server administration, not the Alertify login.

If you already have a customized `.env`, use the frontend URL printed by the
launcher. Keep `.env` private: it contains passwords and encryption material.

> The default `localhost` setup uses HTTP and needs no browser certificate
> installation. A custom hostname requires HTTPS; see
> [Local HTTPS and certificate trust](#local-https-and-certificate-trust).

## What you can do

- **Dashboard:** follow alert status as it changes.
- **Alerts:** configure scheduled checks using available templates.
- **Procedures:** define reusable operations that alerts can invoke.
- **Pipes:** combine ordered Alert and Procedure steps and pass artifacts between them.
- **Hooks:** expose controlled endpoints that trigger alerts and procedures.
- **Configuration and secrets:** manage shared values and sensitive parameters.
- **Status and logs:** inspect workers, execution history, and application events.

### Example use cases

These examples use the included templates. Choose the named template in Alerts
or Procedures, then configure it for your own services and credentials.

- **Check that an API is responding correctly.** Use **Web request - REST** to
  call your application's health endpoint, expect HTTP `200`, and match a response
  body pattern such as `UP`. A response with the wrong status or content produces
  a warning.
- **Catch an expiring certificate before users notice.** Use **HTTPS certificate
  expiry** with your site's address and a warning window of 30 days. The alert
  warns when the certificate is approaching expiration.
- **Verify a user journey in a real browser.** Use **Playwright page monitor** to
  open a login page, fill fields from stored configuration or secrets, click the
  sign-in button, and check that the expected page or text appears. Run it with
  a Playwright worker using Chromium, Firefox, or WebKit.
- **Monitor low stock.** Use **SQL threshold** with a query that returns the
  available quantity of a specific product. Configure it to warn when the stock
  falls below 10 units, so you can replenish it before it runs out.
- **Detect changes in important database records.** Use **SQL watch** to compare
  a query's results with the previous valid execution, for example to track changes
  in a table of application settings.
- **Watch a Kubernetes deployment.** Use **Kubernetes workload health** to check
  the rollout health and container restart counts of your API's Deployment.
  StatefulSets and DaemonSets are supported too.
- **Follow builds and release branches.** Use **GitLab pipelines** to check the
  latest pipeline that actually ran on `main`, including same-project child
  pipelines. Use **Git branch flow** separately to detect when a production branch
  is behind the previous branch in your promotion flow or a promotion would conflict.
- **Create a database backup and copy it to shared storage.** Put a
  **PostgreSQL SQL backup** Procedure followed by **Copy artifact to NFS** in a
  Pipe, passing the backup artifact to the copy step. Other included backup
  templates cover MariaDB SQL dumps, SQL Server `.bak` files, and Oracle Data Pump
  `.dmp` exports.

## Running again and configuration

Run the same launcher whenever you need to rebuild and start the application.
Existing configuration values are preserved, and missing settings are added from
[`.env.template`](.env.template).

The checklist options let you change what happens:

| Option | What it does |
| --- | --- |
| `Skip ...` | Reuses that component without rebuilding or restarting it. It must already be running and usable. |
| `Skip all` | Toggles all component skip options together. |
| `Clean up unused Docker images/cache` | Removes unused images and ordinary build cache from the Docker host, preserving the configured image patterns and dependency cache mounts. |

Cleanup is optional and can affect unused images from other projects on the same
Docker host. Leave it unchecked if you want to keep those images and build cache.
Use skips only for components unaffected by your changes. After changes to shared
configuration, identity, databases, workers, or certificates, run the full stack.

To start everything directly without the checklist:

```powershell
.\run.bat --non-interactive
```

```sh
sh ./run.sh --non-interactive
```

Edit `.env` to customize your installation. The comments in
[`.env.template`](.env.template) describe the settings, including service modes,
worker counts, and external connections. The main address settings are:

| Setting | Default | Purpose |
| --- | --- | --- |
| `APP_PUBLIC_URL` | `http://localhost` | Public application address, before the context path. |
| `APP_CONTEXT_PATH` | `/alertify` | Shared path prefix for the application, API, and identity endpoints. |
| `PUBLIC_PORT` | `80` | Published application port. |

The runner adds `APP_CONTEXT_PATH` to the URL settings automatically. Do not add
it again to `APP_PUBLIC_URL`. When changing the hostname or port, also update the
related backend and identity URLs listed in `.env.template`.

## Local HTTPS and certificate trust

The launcher uses HTTP for `localhost` and subdomains of `.localhost`. Other
hostnames require HTTPS. For these local installations, it generates a server
certificate signed by a local certificate authority (CA).

### Example: use `https://alertify.test/alertify`

If `.env` does not exist yet, run `.\run.bat` on Windows or `sh ./run.sh` on
Linux/macOS and follow the quick start above. The launcher creates it
automatically. For the default local services and `monitoring` realm, update
these entries in `.env`:

```dotenv
APP_PUBLIC_URL=https://alertify.test
BACKEND_PUBLIC_URL=https://alertify.test
KEYCLOAK_PUBLIC_URL=https://alertify.test/identity
OIDC_ISSUER_URI=https://alertify.test/identity/realms/monitoring
APP_CONTEXT_PATH=/alertify
PUBLISHER_TLS_SERVER_NAME=alertify.test
PUBLIC_PORT=443
PUBLIC_HTTP_PORT=80
```

Keep the internal service URLs and local Keycloak administration URL unchanged
for this example. Port 80 redirects to HTTPS on port 443.

Make `alertify.test` resolve to your Docker host. For a browser on that same
computer, add this entry to the hosts file:

```text
127.0.0.1 alertify.test
```

On Windows, open PowerShell **as Administrator** and open the hosts file with:

```powershell
notepad.exe "$env:WINDIR\System32\drivers\etc\hosts"
```

On Linux and macOS, the hosts file is `/etc/hosts`; editing it requires
administrator privileges. This example keeps the application's default loopback
binding and is intended for access from the same computer.

Run the launcher again with no skip options selected. After it prepares HTTPS,
the public CA certificate is available at:

```text
.alertify/certificates/alertify-local-ca.crt
```

### Trust the CA on Windows

> Install only the **public CA certificate generated by your own installation**.
> Adding it to Trusted Root Certification Authorities tells this computer to trust
> certificates issued by that CA. Never export or install the CA private key.

Open PowerShell **as Administrator**, return to the repository folder, and run:

```powershell
Import-Certificate -FilePath ".\.alertify\certificates\alertify-local-ca.crt" -CertStoreLocation "Cert:\LocalMachine\Root"
```

This imports the CA into the Windows machine's Trusted Root Certification
Authorities store. Restart your browser and open
**https://alertify.test/alertify**. See Microsoft's
[`Import-Certificate` reference](https://learn.microsoft.com/en-us/powershell/module/pki/import-certificate)
for the certificate-store options.

Browsers or tools with their own trust store may need the public CA imported
there as well. Linux containers do not inherit the Windows trust store.

### Check HTTPS from the command line

You can verify HTTPS using the exported CA explicitly, including before it has
been installed in the system trust store.

**Windows:**

```powershell
curl.exe --cacert ".\.alertify\certificates\alertify-local-ca.crt" --head https://alertify.test/alertify/
```

If Windows curl reports a revocation-check failure for this offline local CA,
retry with:

```powershell
curl.exe --cacert ".\.alertify\certificates\alertify-local-ca.crt" --ssl-no-revoke --head https://alertify.test/alertify/
```

`--ssl-no-revoke` disables the Schannel revocation lookup for this request while
keeping certificate-chain and hostname verification. Do not use `--insecure`
to validate certificate trust. See the
[curl option reference](https://curl.se/docs/manpage.html#--ssl-no-revoke).

**Linux / macOS:**

```sh
curl --cacert .alertify/certificates/alertify-local-ca.crt --head https://alertify.test/alertify/
```

The CA private key stays in the `${COMPOSE_PROJECT_NAME}-publisher-tls-ca` Docker
volume. If that volume is deleted, a later full startup creates a new CA. Replace
the old trusted certificate with the newly exported public certificate on each
computer that needs to access the application.

## Under the hood

Alertify uses an Angular frontend and a Java 25 backend, with PostgreSQL for
persistence, Redis, and Keycloak for authentication. Standard workers and
Playwright workers execute checks and operations. A publisher provides the public
entry point. The launchers build and coordinate these components through Docker.

## Development Git hook

Activate the versioned `pre-commit` hook in each clone:

```sh
git config --local core.hooksPath .githooks
```

The hook rejects staged local environment files, keys, certificates, runtime
configuration, and generated artifacts. It also prevents publishing these local
customizations to the original repository:

- `frontend/src/app/core/alert-charts/alert-chart-extractors.extended.ts`
- `frontend/src/app/core/alert-messages/alert-message-formatters.extended.ts`
- `frontend/src/app/core/i18n/translations/en.extended.translations.ts`
- `frontend/src/app/core/i18n/translations/es-uy.extended.translations.ts`
- All `.java` files under
  `backend/alert-templates/src/main/java/app/alertify/alerts/templates/custom/`,
  including nested packages.

This check covers additions, modifications, deletions, and renames. A rejected
commit prints a warning listing the files and explaining why they do not belong
in the original repository. The hook leaves the index and working files
untouched. Unstaged customizations do not prevent unrelated commits.

To version these customizations in a fork, enable the exception in that clone:

```sh
git config --local alertify.allowCustomizations true
```

This exempts only the customization files listed above; the other protections
remain active. Restore the default behavior with
`git config --local alertify.allowCustomizations false`.

Hooks do not hide changes from Git or the editor. They are a local guard and can
be bypassed with `git commit --no-verify`. Run the hook regression checks with
Git and Bash installed:

```sh
bash .githooks/pre-commit.test.sh
```

## Troubleshooting

- **Docker is unavailable:** start Docker Desktop or Docker Engine and verify
  that `docker info` succeeds. On Windows, use Linux containers.
- **A port is already in use:** free the conflicting port or update the port and
  corresponding URLs in `.env`. Check both the application and HTTPS redirect
  ports when applicable.
- **The application does not open:** wait for startup to finish, use the frontend
  URL shown by the launcher, and inspect the failed container's logs.
- **Login fails:** use `APP_ADMIN_*` credentials and the configured public URL.
  Switching between hostnames can break the authentication redirects.
- **The browser reports an untrusted certificate:** complete the CA installation
  above and check that the URL hostname matches `PUBLISHER_TLS_SERVER_NAME`.
- **A previous Windows runner is still present:** inspect it before retrying;
  it may still be building or starting services.

Useful diagnostic commands from a terminal:

```sh
docker ps -a --filter label=com.docker.compose.project=monitoring
docker logs --tail 100 monitoring-backend
docker logs --tail 100 monitoring-bootstrap-runner
```

These examples use the default project name, `monitoring`. Adjust the project
filter and application container names if you changed `COMPOSE_PROJECT_NAME`.
`monitoring-bootstrap-runner` is the Windows launcher's fixed container name and
is normally removed when the launcher exits. Review logs for sensitive data
before sharing them.
