package app.alertify.ai;

/** Frontend resource families that should be invalidated after a successful write tool. */
public enum AiRefreshTarget {
    ALERTS,
    PROCEDURES,
    PIPES,
    HOOKS,
    CONFIGURATIONS,
    DASHBOARD,
    SYSTEM,
    ALL
}
