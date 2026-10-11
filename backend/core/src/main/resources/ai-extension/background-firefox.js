const api = globalThis.browser;
const config = globalThis.ALERTIFY_CODEX_CONFIG;

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

function isCallbackUrl(value) {
  try {
    const candidate = new URL(value);
    const callback = new URL(config.callbackUri);
    return candidate.origin === callback.origin && candidate.pathname === callback.pathname;
  } catch {
    return false;
  }
}

async function clearAttempt() {
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

  await api.storage.session.set({
    callbackTicket: message.callbackTicket,
    ticketExpiresAt: message.ticketExpiresAt,
  });
}

async function redirectCallback(details) {
  if (!isCallbackUrl(details.url))
    return {};

  const stored = await api.storage.session.get(['callbackTicket', 'ticketExpiresAt']);
  if (!stored.callbackTicket || Date.parse(stored.ticketExpiresAt) <= Date.now())
    return {};

  const callback = new URL(details.url);
  return { redirectUrl: `${api.runtime.getURL('callback.html')}#${callback.search.slice(1)}` };
}

api.webRequest.onBeforeRequest.addListener(
  redirectCallback,
  { urls: [config.callbackMatch], types: ['main_frame'] },
  ['blocking'],
);

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
