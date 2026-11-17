package com.jantursky.casement.compose.platform

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The page the desktop renderer loads. It holds one sandboxed iframe and relays JSON-RPC between that frame and the
 * host. The frame has an opaque origin (`sandbox` without `allow-same-origin`) and cannot navigate the page, and the
 * page's own policy lets frames load nothing but `about:` documents, so the frame cannot be sent anywhere either.
 *
 * Messages to the host leave through `alert('casement:' + text)`, which the web engine hands to the host.
 */
internal fun wrapperDocument(): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src about:; script-src 'unsafe-inline'; style-src 'unsafe-inline'">
    <style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}iframe{border:0;width:100%;height:100%;display:block;background:transparent}</style>
    </head><body><script>
    (function () {
      'use strict';
      var frame = null, loads = 0;
      function toHost(text) { alert('casement:' + text); }
      window.__start = function (html, allow) {
        frame = document.createElement('iframe');
        frame.setAttribute('sandbox', 'allow-scripts allow-forms');
        frame.setAttribute('referrerpolicy', 'no-referrer');
        frame.addEventListener('load', function () {
          loads += 1;
          // The document loads once. A second load means the frame was moved: cut it off.
          if (loads > 1) { toHost(JSON.stringify({ casement: 'navigation' })); frame.remove(); frame = null; }
        });
        // The document is set before the frame is attached, so its one load is the only load there should be.
        frame.srcdoc = html;
        document.body.appendChild(frame);
      };
      window.__toApp = function (text) {
        if (frame && frame.contentWindow) frame.contentWindow.postMessage(JSON.parse(text), '*');
      };
      window.addEventListener('message', function (e) {
        if (!frame || e.source !== frame.contentWindow) return;
        var d = e.data;
        if (d && typeof d === 'object' && d.jsonrpc === '2.0') toHost(JSON.stringify(d));
        else if (d && d.casement === 'csp') {
          toHost(JSON.stringify({ casement: 'csp', directive: String(d.directive || ''), blocked: String(d.blocked || '') }));
        }
      });
      document.addEventListener('securitypolicyviolation', function (e) {
        toHost(JSON.stringify({ casement: 'csp', directive: e.violatedDirective, blocked: e.blockedURI }));
      });
      toHost(JSON.stringify({ casement: 'ready' }));
    })();
    </script></body></html>
    """.trimIndent()

/**
 * The app's document as the frame loads it: the policy first, so it is in force before any of the app's own
 * scripts, then a small script that reports CSP violations the frame sees to the page around it.
 */
internal fun appDocument(
    html: String,
    csp: String,
): String {
    val meta = """<meta http-equiv="Content-Security-Policy" content="${csp.replace("\"", "&quot;")}">"""
    val report =
        "<script>document.addEventListener('securitypolicyviolation',function(e){" +
            "parent.postMessage({casement:'csp',directive:e.violatedDirective,blocked:e.blockedURI},'*')});</script>"
    val doctype = Regex("^\\s*(<!--[\\s\\S]*?-->\\s*)*<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
    val head = meta + report
    return if (doctype != null) {
        html.substring(0, doctype.range.last + 1) + head + html.substring(doctype.range.last + 1)
    } else {
        head + html
    }
}

/** [text] as a JavaScript string literal. */
internal fun jsString(text: String): String = Json.encodeToString(String.serializer(), text)
