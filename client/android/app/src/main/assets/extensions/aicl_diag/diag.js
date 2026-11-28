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

  try {
    send(
      "diag_env",
      location.href,
      "ua=" + navigator.userAgent + " vp=" + window.innerWidth + "x" + window.innerHeight + " dpr=" + window.devicePixelRatio
    );
  } catch (_) {}

  window.addEventListener(
    "error",
    function (ev) {
      try {
        var t = ev && ev.target;
        if (t && t !== window && t.tagName) {
          var u = t.currentSrc || t.src || t.href || "";
          var d = t.tagName + " complete=" + t.complete + " nw=" + t.naturalWidth + " nh=" + t.naturalHeight;
          send("dom_error", u, d);
        }
      } catch (_) {}
    },
    true
  );

  window.addEventListener("message", function (ev) {
    try {
      if (ev && ev.source === window && ev.data && ev.data.__aiclDiag === true) {
        send(ev.data.kind, ev.data.url, ev.data.detail);
      }
    } catch (_) {}
  });

  try {
    var script = document.createElement("script");
    script.textContent =
      "(function(){" +
      "function emit(kind,url,detail){try{window.postMessage({__aiclDiag:true,kind:kind,url:String(url||''),detail:String(detail||'')},'*');}catch(_){}}" +
      "try{var origDecode=HTMLImageElement.prototype.decode;if(origDecode){HTMLImageElement.prototype.decode=function(){var self=this;return origDecode.apply(this,arguments).catch(function(e){emit('img_decode_err',self.currentSrc||self.src,(e&&e.message?e.message:String(e))+' complete='+self.complete+' nw='+self.naturalWidth+' nh='+self.naturalHeight);throw e;});};}}catch(_){}" +
      "try{var OrigImg=window.Image;if(OrigImg){var WrappedImg=function(w,h){var img=arguments.length===0?new OrigImg():arguments.length===1?new OrigImg(w):new OrigImg(w,h);try{img.addEventListener('error',function(){emit('new_img_err',img.currentSrc||img.src,'complete='+img.complete+' nw='+img.naturalWidth+' nh='+img.naturalHeight);});}catch(_){}return img;};WrappedImg.prototype=OrigImg.prototype;window.Image=WrappedImg;}}catch(_){}" +
      "try{var origFetch=window.fetch;if(origFetch){window.fetch=function(input,init){var u=typeof input==='string'?input:(input&&input.url)||'';return origFetch.apply(this,arguments).then(function(res){if(!res.ok){emit('fetch_err',res.url||u,'status='+res.status);}return res;}).catch(function(e){emit('fetch_err',u,e&&e.message?e.message:String(e));throw e;});};}}catch(_){}" +
      "try{var origErr=console.error;console.error=function(){try{var msg=Array.prototype.slice.call(arguments).map(function(x){return typeof x==='string'?x:(x&&x.message?x.message:String(x));}).join(' ');emit('console_err',location.href,msg.slice(0,220));}catch(_){}return origErr.apply(this,arguments);};}catch(_){}" +
      "window.addEventListener('unhandledrejection',function(ev){try{var r=ev&&ev.reason;emit('rejection',location.href,r&&r.message?r.message:String(r));}catch(_){}});" +
      "})();";
    (document.documentElement || document.head || document).appendChild(script);
    script.remove();
  } catch (_) {}
})();
