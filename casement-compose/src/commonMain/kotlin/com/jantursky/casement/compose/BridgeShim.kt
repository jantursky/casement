package com.jantursky.casement.compose

/**
 * The script every renderer injects before the app's own scripts run.
 *
 * In a top-level web view `window.parent` is `window`, so an MCP App that calls `window.parent.postMessage` talks
 * to itself. The shim sends JSON-RPC messages to the host instead, and turns the host's answers into `message`
 * events whose `source` is `window`, which is what the ext-apps transport checks. Other `postMessage` calls keep
 * working. Control messages (`{"casement": ...}`) tell the host that the page is ready and report CSP violations.
 *
 * [send] and [receive] are JavaScript snippets supplied by the platform: a function expression taking the text to
 * send, and a statement that calls `deliver(text)` for every host message.
 */
internal fun bridgeShim(
    send: String,
    receive: String,
): String =
    """
    (function () {
      'use strict';
      var send = $send;
      var original = window.postMessage;
      function deliver(text) {
        var data;
        try { data = JSON.parse(text); } catch (e) { return; }
        window.dispatchEvent(new MessageEvent('message', {
          data: data, origin: window.location.origin, source: window
        }));
      }
      $receive
      window.postMessage = function (message) {
        if (message && typeof message === 'object' && message.jsonrpc === '2.0') {
          send(JSON.stringify(message));
          return;
        }
        return original.apply(window, arguments);
      };
      document.addEventListener('securitypolicyviolation', function (e) {
        send(JSON.stringify({ casement: 'csp', directive: e.violatedDirective, blocked: e.blockedURI }));
      });
      send(JSON.stringify({ casement: 'ready' }));
    })();
    """.trimIndent()
