package app.alertify.worker.contract;

/**
 * Git hosting providers a {@code GIT_SECRET} can describe. {@link #OTHER}
 * covers any provider not listed here, including self-hosted instances whose
 * host does not identify the provider (e.g. Gitea, Azure DevOps).
 */
public enum GitProvider {

    GITHUB,
    GITLAB,
    BITBUCKET,
    OTHER
}
