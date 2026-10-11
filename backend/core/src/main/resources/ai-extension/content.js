(() => {
  const config = globalThis.ALERTIFY_CODEX_CONFIG;
  const api = globalThis.browser ?? globalThis.chrome;
  const pageSource = 'alertify-ai-page';
  const extensionSource = 'alertify-codex-extension';

  function isAlertifyPage() {
    const base = new URL(config.publicBaseUrl);
    const basePath = base.pathname.replace(/\/$/, '');
    return location.origin === base.origin
      && (location.pathname === basePath || location.pathname.startsWith(`${basePath}/`));
  }

  function respond(message) {
    window.postMessage({ source: extensionSource, instanceId: config.instanceId, ...message }, config.pageOrigin);
  }

  window.addEventListener('message', (event) => {
    if (event.source !== window || event.origin !== config.pageOrigin || !isAlertifyPage())
      return;

    const message = event.data;
    if (!message || message.source !== pageSource)
      return;

    if (message.type === 'PING') {
      respond({ type: 'READY', browser: config.browser, protocolVersion: config.protocolVersion });
      return;
    }

    if (message.instanceId !== config.instanceId || message.protocolVersion !== config.protocolVersion
        || message.type !== 'PREPARE' || typeof message.requestId !== 'string')
      return;

    api.runtime.sendMessage({
      type: 'PREPARE',
      instanceId: config.instanceId,
      protocolVersion: message.protocolVersion,
      callbackUri: message.callbackUri,
      callbackTicket: message.callbackTicket,
      ticketExpiresAt: message.ticketExpiresAt,
    }).then((response) => {
      if (!response?.successful)
        throw new Error('Extension preparation failed');

      respond({ type: 'PREPARED', requestId: message.requestId, successful: true });
    })
      .catch(() => respond({ type: 'PREPARED', requestId: message.requestId, successful: false }));
  });
})();
