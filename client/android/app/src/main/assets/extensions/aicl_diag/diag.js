(function () {
  "use strict";

  function send(kind, url, detail) {
    try {
      browser.runtime.sendNativeMessage("aicl_diag", {
        kind: String(kind || ""),
        url: String(url || ""),
        detail: String(detail || "")
      });
    } catch (_) {}
  }

  function shortPath(u) {
    return String(u || "").replace("https://get.aliceincradle.dev", "");
  }

  try {
    send(
      "diag_env",
      location.href,
      "vis=" + document.visibilityState +
        " hidden=" + document.hidden +
        " focus=" + document.hasFocus() +
        " vp=" + window.innerWidth + "x" + window.innerHeight +
        " dpr=" + window.devicePixelRatio
    );
  } catch (_) {}

  try {
    var rafFired = false;
    var t0 = Date.now();
    requestAnimationFrame(function () {
      rafFired = true;
      send(
        "raf_ok",
        location.href,
        "ms=" + (Date.now() - t0) + " vis=" + document.visibilityState + " hidden=" + document.hidden
      );
    });
    setTimeout(function () {
      if (!rafFired) {
        send(
          "raf_stalled",
          location.href,
          "after=1500ms vis=" + document.visibilityState + " hidden=" + document.hidden + " focus=" + document.hasFocus()
        );
      }
    }, 1500);
  } catch (_) {}

  window.addEventListener(
    "error",
    function (ev) {
      try {
        var t = ev && ev.target;
        if (t && t !== window && t.tagName) {
          var u = t.currentSrc || t.src || t.href || "";
          var d = t.tagName + " c=" + t.complete + " nw=" + t.naturalWidth + " nh=" + t.naturalHeight;
          send("dom_error", shortPath(u), d);
        }
      } catch (_) {}
    },
    true
  );

  function runAudit(tag) {
    try {
      var res = (performance && performance.getEntriesByType) ? (performance.getEntriesByType("resource") || []) : [];
      var imgRes = [];
      var badImgRes = [];
      var badOtherRes = [];
      for (var i = 0; i < res.length; i++) {
        var e = res[i];
        var name = String(e.name || "");
        var st = typeof e.responseStatus === "number" ? e.responseStatus : -1;
        var tr = typeof e.transferSize === "number" ? e.transferSize : -1;
        var db = typeof e.decodedBodySize === "number" ? e.decodedBodySize : -1;
        if (name.indexOf("/_img/") !== -1) {
          imgRes.push(e);
          if ((st !== -1 && st !== 200) || (tr === 0 && db === 0)) {
            badImgRes.push(shortPath(name) + "(st=" + st + ",tr=" + tr + ",db=" + db + ")");
          }
        } else if (st !== -1 && st !== 200) {
          badOtherRes.push(shortPath(name) + "(st=" + st + ")");
        }
      }

      var imgs = Array.prototype.slice.call(document.images || []);
      var brokenDom = [];
      for (var j = 0; j < imgs.length; j++) {
        var img = imgs[j];
        if (!img.complete || img.naturalWidth === 0) {
          brokenDom.push(
            shortPath(img.currentSrc || img.src) + "(c=" + img.complete + ",nw=" + img.naturalWidth + ",nh=" + img.naturalHeight + ")"
          );
        }
      }

      var hasErrText = !!(
        document.body &&
        document.body.innerText &&
        document.body.innerText.indexOf("Some images could not be loaded") !== -1
      );
      var uaTag = navigator.userAgent.indexOf("Android") !== -1 ? "Android" : "Win";
      send(
        tag,
        "imgRes=" + imgRes.length + " badRes=" + badImgRes.length + " domImg=" + imgs.length + " nw0=" + brokenDom.length + " errText=" + hasErrText,
        "ua=" + uaTag + " vis=" + document.visibilityState
      );
      if (badImgRes.length > 0) {
        send(tag + "_bad_res", "", badImgRes.slice(0, 6).join("; "));
      }
      if (brokenDom.length > 0) {
        send(tag + "_nw0", "", brokenDom.slice(0, 6).join("; "));
      }
      if (badOtherRes.length > 0) {
        send(tag + "_other", "", badOtherRes.slice(0, 4).join("; "));
      }
    } catch (err) {
      send(tag + "_err", "", err && err.message ? err.message : String(err));
    }
  }

  setTimeout(function () { runAudit("audit_3s"); }, 3000);
  setTimeout(function () { runAudit("audit_6s"); }, 6000);
  setTimeout(function () { runAudit("audit_9s"); }, 9000);

  try {
    var errReported = false;
    var obs = new MutationObserver(function () {
      if (errReported) return;
      if (
        document.body &&
        document.body.innerText &&
        document.body.innerText.indexOf("Some images could not be loaded") !== -1
      ) {
        errReported = true;
        obs.disconnect();
        runAudit("audit_err");
      }
    });
    obs.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  } catch (_) {}
})();
