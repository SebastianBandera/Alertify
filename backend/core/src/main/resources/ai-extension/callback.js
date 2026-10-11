(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  const config = globalThis.ALERTIFY_CODEX_CONFIG;
  const spanish = navigator.language.toLowerCase().startsWith('es');
  const title = document.querySelector('h1');
  const message = document.querySelector('p');
  const close = document.querySelector('button');

  function show(successful) {
    document.body.dataset.state = successful ? 'success' : 'error';
    title.textContent = successful
      ? (spanish ? 'Sesión iniciada' : 'Signed in')
      : (spanish ? 'No se pudo iniciar sesión' : 'Sign-in failed');
    message.textContent = successful
      ? (spanish ? 'Podés cerrar esta pestaña y volver a Alertify.' : 'You can close this tab and return to Alertify.')
      : (spanish ? 'Volvé a Alertify e iniciá un nuevo intento.' : 'Return to Alertify and start a new attempt.');
    close.textContent = spanish ? 'Cerrar' : 'Close';
  }

  async function run() {
    let successful = false;
    try {
      const stored = await api.storage.session.get(['callbackTicket', 'ticketExpiresAt']);
      if (!stored.callbackTicket || Date.parse(stored.ticketExpiresAt) <= Date.now())
        throw new Error('Missing or expired callback ticket');

      const parameters = new URLSearchParams(location.hash.replace(/^#\??/, ''));
      const response = await fetch(config.relayUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          callbackTicket: stored.callbackTicket,
          code: parameters.get('code') ?? '',
          state: parameters.get('state') ?? '',
          scope: parameters.get('scope') ?? '',
          clientId: parameters.get('client_id') ?? '',
        }),
      });
      successful = response.ok;
    } catch {
      successful = false;
    } finally {
      await api.runtime.sendMessage({ type: 'CLEAR' }).catch(() => undefined);
      show(successful);
    }
  }

  close.addEventListener('click', () => window.close());
  void run();
})();
