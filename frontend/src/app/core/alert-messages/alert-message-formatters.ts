import { AlertMessageContext, AlertMessageFormatters } from './alert-message-formatter';
import { EXTENDED_ALERT_MESSAGE_FORMATTERS } from './alert-message-formatters.extended';

const TEMPLATES = 'app.alertify.alerts.templates.';

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null;
}

function count(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function record(value: unknown): Readonly<Record<string, unknown>> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Readonly<Record<string, unknown>>
    : null;
}

function records(value: unknown): readonly Readonly<Record<string, unknown>>[] {
  return Array.isArray(value) ? value.map(record).filter((item): item is Readonly<Record<string, unknown>> => item !== null) : [];
}

function join(...parts: readonly (string | null | undefined)[]): string {
  return parts.filter((part): part is string => !!part).join(' · ');
}

/* A known failure code reads as a sentence; an unknown one is still shown, as is. */
function failureMessage(context: AlertMessageContext, message: Readonly<Record<string, unknown>>): string | null {
  const reason = text(message['failureReason']);
  if (reason === null) return null;
  return context.translate(`alertMessage.reason.${reason}`) ?? context.translate('alertMessage.failure', { reason });
}

function failure(context: AlertMessageContext): string | null {
  return failureMessage(context, context.message);
}

function playwrightBrowser(value: unknown): string {
  switch (text(value)) {
    case 'chromium': return 'Chromium';
    case 'firefox': return 'Firefox';
    case 'webkit': return 'WebKit';
    default: return text(value) ?? 'Playwright';
  }
}

function endpoint(context: AlertMessageContext): string | null {
  const host = text(context.message['host']);
  const port = context.message['port'];
  return host === null ? null : port === undefined || port === null ? host : `${host}:${port}`;
}

/**
 * Formatters for the templates that ship with Alertify, keyed by template
 * class. Extended formatters are merged last, so a fork can add its own
 * templates or replace one of these without editing this file.
 */
export const ALERT_MESSAGE_FORMATTERS: AlertMessageFormatters = {
  [`${TEMPLATES}SqlThresholdAlertTemplate`]: (context) => {
    const message = context.message;
    const condition = context.translate(`alertMessage.thresholdType.${message['thresholdType']}`, {
      threshold: context.formatNumber(message['threshold']),
    });
    return failure(context) ?? join(
      context.translate('alertMessage.sqlThreshold.value', { value: context.formatNumber(message['value']) }),
      condition === null ? null : context.translate('alertMessage.sqlThreshold.condition', { condition }),
      text(message['detail']),
    );
  },

  [`${TEMPLATES}SqlStatusAlertTemplate`]: (context) =>
    failure(context) ?? text(context.message['detail']) ?? context.translate('alertMessage.sqlStatus.status', { status: context.message['value'] }),

  [`${TEMPLATES}SqlWatchAlertTemplate`]: (context) => {
    const message = context.message;
    const rows = context.formatNumber(message['rows']);
    if (message['initialized'] === true) return context.translate('alertMessage.sqlWatch.initialized', { rows });
    if (message['structuralChange'] === true) return context.translate('alertMessage.sqlWatch.structuralChange', { rows });
    const added = count(message['addedRows']);
    const removed = count(message['removedRows']);
    const modified = count(message['modifiedRows']);
    if (added + removed + modified === 0) return context.translate('alertMessage.sqlWatch.unchanged', { rows });
    return context.translate('alertMessage.sqlWatch.changed', {
      added: context.formatNumber(added), removed: context.formatNumber(removed), modified: context.formatNumber(modified), rows,
    });
  },

  [`${TEMPLATES}HttpsCertificateExpiryAlertTemplate`]: (context) => {
    const message = context.message;
    const validity = message['notYetValid'] === true
      ? context.translate('alertMessage.certificate.notYetValid', { date: context.formatDate(message['notBefore']) })
      : message['expired'] === true
        ? context.translate('alertMessage.certificate.expired', { date: context.formatDate(message['notAfter']) })
        : context.translate('alertMessage.certificate.expiresIn', {
          days: context.formatNumber(message['daysRemaining']), date: context.formatDate(message['notAfter']),
        });
    return join(validity, message['hostnameMatch'] === false ? context.translate('alertMessage.certificate.hostnameMismatch') : null);
  },

  [`${TEMPLATES}InternetConnectionAlertTemplate`]: (context) => {
    const message = context.message;
    if (message['timedOut'] === true) {
      return context.translate('alertMessage.internet.timeout', { seconds: context.formatNumber(message['timeoutSeconds']) });
    }
    return failure(context) ?? context.translate('alertMessage.http', {
      code: message['statusCode'], latency: context.formatDuration(message['latencyMs']),
    });
  },

  [`${TEMPLATES}TcpConnectionAlertTemplate`]: (context) => {
    const message = context.message;
    const target = endpoint(context);
    const reason = failure(context);
    if (reason !== null) return join(target, reason);
    return message['portOpen'] === true
      ? context.translate('alertMessage.tcp.open', { target, latency: context.formatDuration(message['connectMs']) })
      : context.translate('alertMessage.tcp.closed', { target });
  },

  [`${TEMPLATES}DatabaseConnectionAlertTemplate`]: (context) => {
    const message = context.message;
    const reason = failure(context);
    if (reason !== null) return join(endpoint(context), reason);
    const product = [text(message['productName']) ?? text(message['engine']), text(message['productVersion'])]
      .filter((part): part is string => part !== null)
      .join(' ');
    return context.translate('alertMessage.database.connected', {
      product, latency: context.formatDuration(message['connectMs']),
    });
  },

  [`${TEMPLATES}WebRequestAlertTemplate`]: (context) => {
    const message = context.message;
    const request = message['statusCode'] === undefined
      ? text(message['method'])
      : `${text(message['method']) ?? ''} HTTP ${message['statusCode']}`.trim();
    const reason = failure(context);
    return reason !== null
      ? join(request, reason)
      : join(request, context.formatDuration(message['latencyMs']));
  },

  [`${TEMPLATES}PlaywrightPageAlertTemplate`]: (context) => {
    const message = context.message;
    const browserResults = Array.isArray(message['browserResults'])
      ? message['browserResults'].filter((result): result is Readonly<Record<string, unknown>> =>
        result !== null && typeof result === 'object' && !Array.isArray(result))
      : [];
    if (browserResults.length > 0) {
      const warnings = browserResults.filter((result) => result['status'] === 'WARN');
      const successful = count(message['successfulBrowserCount']);
      const total = count(message['browserCount']);
      if (warnings.length === 0) {
        return context.translate('alertMessage.playwright.browsersCompleted', {
          successful: context.formatNumber(successful),
          total: context.formatNumber(total),
          commands: context.formatNumber(message['commandCount']),
        });
      }

      const warningSummary = warnings.map((result) => {
        const code = text(result['failureReason']);
        const timing = code === 'loadTimeout' || code === 'reloadTimeout'
          ? context.translate('alertMessage.playwright.timing', { expected: `${context.formatNumber(message['loadTimeoutSeconds'])} s` })
          : null;
        const line = result['failedLine'];
        const detail = join(
          failureMessage(context, result),
          timing,
          line === undefined || line === null ? null : context.translate('alertMessage.playwright.line', { line }),
        );
        return `${playwrightBrowser(result['browser'])}: ${detail}`;
      }).join('; ');
      return context.translate('alertMessage.playwright.browsersWarning', {
        successful: context.formatNumber(successful),
        total: context.formatNumber(total),
        warnings: warningSummary,
      });
    }

    const reason = failure(context);
    if (reason !== null) {
      const line = message['failedLine'];
      const code = text(message['failureReason']);
      const timing = code === 'loadTimeout' || code === 'reloadTimeout'
        ? context.translate('alertMessage.playwright.timing', { expected: `${context.formatNumber(message['loadTimeoutSeconds'])} s` })
        : null;
      return join(reason, timing, line === undefined || line === null ? null : context.translate('alertMessage.playwright.line', { line }));
    }
    return context.translate('alertMessage.playwright.completed', {
      completed: context.formatNumber(message['completedCommandCount']),
      total: context.formatNumber(message['commandCount']),
      load: context.formatDuration(message['loadDurationMs']),
    });
  },

  [`${TEMPLATES}GitBranchFlowAlertTemplate`]: (context) => {
    const message = context.message;
    const reason = failure(context);
    if (reason !== null) return reason;
    const warnings = Array.isArray(message['warnings']) ? message['warnings'] : [];
    const parts: (string | null)[] = [];
    if (warnings.includes('commits_outside_flow')) {
      const outside = Array.isArray(message['commitsOutsideFlow']) ? message['commitsOutsideFlow'] : [];
      const total = outside.reduce((sum: number, entry) => sum + count((entry as Record<string, unknown>)['count']), 0);
      parts.push(context.translate('alertMessage.git.outsideFlow', { count: context.formatNumber(total) }));
    }
    if (warnings.includes('merge_conflict')) parts.push(context.translate('alertMessage.git.mergeConflict'));
    if (warnings.includes('deployment_delayed')) {
      const transitions = Array.isArray(message['transitions']) ? message['transitions'] : [];
      const last = transitions.at(-1) as Record<string, unknown> | undefined;
      parts.push(context.translate('alertMessage.git.deploymentDelayed', { days: context.formatNumber(last?.['oldestPendingAgeDays']) }));
    }
    return parts.length > 0 ? join(...parts) : context.translate('alertMessage.git.upToDate');
  },

  [`${TEMPLATES}GitLabPipelineAlertTemplate`]: (context) => {
    const topLevelFailure = failure(context);
    if (topLevelFailure !== null) return topLevelFailure;

    const summaries = records(context.message['branchResults']).map((branch) => {
      const name = text(branch['branch']) ?? '?';
      const reason = failureMessage(context, branch);
      if (reason !== null) return `${name}: ${reason}`;
      if (branch['outcome'] === 'no_executed_pipeline') {
        return context.translate('alertMessage.gitLabPipeline.noExecuted', { branch: name });
      }

      const pipeline = record(branch['pipeline']);
      const status = text(pipeline?.['status']) ?? '?';
      const branchWarnings = Array.isArray(branch['warnings']) ? branch['warnings'] : [];
      const childWarnings: string[] = [];
      const visitChildren = (children: unknown): void => {
        for (const child of records(children)) {
          if (child['warning'] === true) childWarnings.push(`#${child['id']} (${text(child['status']) ?? '?'})`);
          visitChildren(child['childPipelines']);
        }
      };
      visitChildren(branch['childPipelines']);

      const parentSummary = branchWarnings.some((warning) => warning !== 'child_pipeline_not_success')
        ? context.translate('alertMessage.gitLabPipeline.warning', { branch: name, status })
        : context.translate('alertMessage.gitLabPipeline.healthy', { branch: name, status });
      const childSummary = childWarnings.length > 0
        ? context.translate('alertMessage.gitLabPipeline.childrenWarning', { children: childWarnings.join(', ') })
        : null;
      return join(parentSummary, childSummary);
    });
    return summaries.join('; ');
  },

  [`${TEMPLATES}devtools.SimulatedResultAlertTemplate`]: (context) => text(context.message['message']),

  [`${TEMPLATES}devtools.SimulatedLongRunningAlertTemplate`]: (context) =>
    context.translate('alertMessage.simulated.duration', { duration: context.formatDuration(context.message['sleepMilliseconds']) }),

  [`${TEMPLATES}devtools.SimulatedLongRunningPlaywrightAlertTemplate`]: (context) =>
    context.translate('alertMessage.simulated.duration', { duration: context.formatDuration(context.message['sleepMilliseconds']) }),

  ...EXTENDED_ALERT_MESSAGE_FORMATTERS,
};
