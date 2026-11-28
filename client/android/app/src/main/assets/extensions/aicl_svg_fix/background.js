(function () {
  "use strict";

  var SVG_FILTER = {
    urls: ["https://get.aliceincradle.dev/*.svg*"],
    types: ["image", "xmlhttprequest", "other"]
  };

  browser.webRequest.onBeforeSendHeaders.addListener(
    function (details) {
      var headers = details.requestHeaders || [];
      var filtered = [];
      for (var i = 0; i < headers.length; i++) {
        var name = String(headers[i].name || "").toLowerCase();
        if (name !== "if-none-match" && name !== "if-modified-since") {
          filtered.push(headers[i]);
        }
      }
      return { requestHeaders: filtered };
    },
    SVG_FILTER,
    ["blocking", "requestHeaders"]
  );

  browser.webRequest.onBeforeRequest.addListener(
    function (details) {
      var filter = browser.webRequest.filterResponseData(details.requestId);
      var chunks = [];

      filter.ondata = function (event) {
        if (event && event.data && event.data.byteLength > 0) {
          chunks.push(new Uint8Array(event.data));
        }
      };

      filter.onstop = function () {
        var totalLen = 0;
        for (var i = 0; i < chunks.length; i++) {
          totalLen += chunks[i].byteLength;
        }
        if (totalLen === 0) {
          try { filter.close(); } catch (_) {}
          return;
        }

        var merged = new Uint8Array(totalLen);
        var offset = 0;
        for (var j = 0; j < chunks.length; j++) {
          merged.set(chunks[j], offset);
          offset += chunks[j].byteLength;
        }

        try {
          var text = new TextDecoder("utf-8").decode(merged);
          var patched = text.replace(/<svg\b([^>]*)>/i, function (full, attrs) {
            if (/\bwidth\s*=/i.test(attrs) || /\bheight\s*=/i.test(attrs)) {
              return full;
            }
            var vb = attrs.match(
              /\bviewBox\s*=\s*["']\s*([0-9.+-]+)[,\s]+([0-9.+-]+)[,\s]+([0-9.+-]+)[,\s]+([0-9.+-]+)\s*["']/i
            );
            if (!vb) {
              return full;
            }
            var w = parseFloat(vb[3]);
            var h = parseFloat(vb[4]);
            if (!isFinite(w) || !isFinite(h) || w <= 0 || h <= 0) {
              return full;
            }
            return '<svg width="' + vb[3] + '" height="' + vb[4] + '"' + attrs + '>';
          });
          var out = new TextEncoder().encode(patched);
          filter.write(out);
        } catch (_) {
          filter.write(merged);
        }
        try { filter.close(); } catch (_) {}
      };

      filter.onerror = function () {
        try { filter.close(); } catch (_) {}
      };

      return {};
    },
    SVG_FILTER,
    ["blocking"]
  );
})();
