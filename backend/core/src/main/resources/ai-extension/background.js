if (!globalThis.ALERTIFY_CODEX_CONFIG)
  globalThis.importScripts('config.js');

const api = globalThis.browser ?? globalThis.chrome;
const config = globalThis.ALERTIFY_CODEX_CONFIG;
const callbackRuleId = 1;

function isAlertifyUrl(value) {
  try {
    const candidate = new URL(value);
    const base = new URL(config.publicBaseUrl);
    const basePath = base.pathname.replace(/\/$/, '');
    return candidate.origin === base.origin
      && (candidate.pathname === basePath || candidate.pathname.startsWith(`${basePath}/`));
  } catch {
    return false;
  }
}

async function clearAttempt() {
  await api.declarativeNetRequest.updateSessionRules({ removeRuleIds: [callbackRuleId] });
  await api.storage.session.remove(['callbackTicket', 'ticketExpiresAt']);
}

async function prepare(message, sender) {
  if (!isAlertifyUrl(sender.tab?.url))
    throw new Error('Untrusted Alertify page');

  if (message.instanceId !== config.instanceId || message.protocolVersion !== config.protocolVersion
      || message.callbackUri !== config.callbackUri)
    throw new Error('Extension configuration mismatch');

  if (typeof message.callbackTicket !== 'string' || message.callbackTicket.length < 32)
    throw new Error('Invalid callback ticket');

  if (!Number.isFinite(Date.parse(message.ticketExpiresAt)) || Date.parse(message.ticketExpiresAt) <= Date.now())
    throw new Error('Expired callback ticket');

  const callbackPage = api.runtime.getURL('callback.html');
  await api.storage.session.set({
    callbackTicket: message.callbackTicket,
    ticketExpiresAt: message.ticketExpiresAt,
  });
  await api.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [callbackRuleId],
    addRules: [{
      id: callbackRuleId,
      priority: 1,
      action: {
        type: 'redirect',
        redirect: { regexSubstitution: `${callbackPage}#\\1` },
      },
      condition: {
        regexFilter: config.callbackRegex,
        resourceTypes: ['main_frame'],
      },
    }],
  });
}

api.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const operation = message?.type === 'PREPARE'
    ? prepare(message, sender)
    : message?.type === 'CLEAR' && sender.url?.startsWith(api.runtime.getURL(''))
      ? clearAttempt()
      : Promise.reject(new Error('Unsupported extension message'));

  operation.then(() => sendResponse({ successful: true }))
    .catch(() => sendResponse({ successful: false }));
  return true;
});
