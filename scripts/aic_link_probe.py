#!/usr/bin/env python3
"""AIC 本体包动态取链验证脚本（P1/P2 实测工具）。

结论来源：见同目录 AIC_取链实测笔记.md。

取链链（实测还原）：
  [1] https://get.aliceincradle.dev/win/            年龄门 Agree -> 渲染 "Current Version" 与 Download 按钮
  [2] https://api.aliceincradle.dev/get/win/latest/verification/<token>   需服务端签发 token，裸请求 301 -> redirection-error
  [3] https://vanilla.shiro.dev/f/<code>/<filename>  稳定公共短链，纯 HTTP 302 -> Location 即新鲜签名直链
  [4] https://dl.aliceincradle.dev/vanilla/<...>?x-oss-*   阿里云 OSS V4 签名，x-oss-expires=3600，前置 Cloudflare

因此取链 = 用 [3] 现取签名；下载 = 携带 [4] 的 cf_clearance（浏览器过 CF 后获得）。
用法：
  python3 aic_link_probe.py resolve                 纯 HTTP 取新签名并打印 TTL
  python3 aic_link_probe.py resolve --twice         对比两次签名是否刷新
  python3 aic_link_probe.py probe                   取链后探测可下载性（魔数/CF）
  python3 aic_link_probe.py capture                 Playwright 走年龄门点击，捕获短链与版本号
  python3 aic_link_probe.py download -o out.7z      带 cookie 下载并校验魔数
"""
import argparse
import datetime
import json
import os
import sys
import time
import urllib.parse

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')

PAGE = 'https://get.aliceincradle.dev/win/'
VERIFY_TMPL = 'https://api.aliceincradle.dev/get/win/latest/verification/{token}'
SHORT_BASE = 'https://vanilla.shiro.dev/f/'
# 实测得到：当前版本 0.30i 对应的短码与文件名（版本升级时会变，需 capture 刷新）
KNOWN_SHORT = 'Oz4hM'
KNOWN_FILE = 'AliceInCradle Win ver030i.7z'
import tempfile

# 缓存目录：只放非签名数据（短码/版本/CF cookie），默认落在系统临时目录，避免污染交付目录
CACHE_DIR = os.environ.get('AIC_CACHE_DIR') or os.path.join(tempfile.gettempdir(), 'aic_link_probe')
os.makedirs(CACHE_DIR, exist_ok=True)
COOKIE_FILE = os.path.join(CACHE_DIR, 'cf_cookies.json')
SHORTLINK_CACHE = os.path.join(CACHE_DIR, 'shortlink_cache.json')

MAGIC_7Z = bytes.fromhex('377a1a27')
MAGIC_ZIP = bytes.fromhex('504b0304')
RETRIES_NET = 4


def log(tag, msg):
    print('[%s] %s' % (tag, msg))


def now_utc():
    return datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)


def http_get(url, **kw):
    """带退避的 GET；仅网络类异常重试。返回 response 或 None。"""
    import requests
    s = kw.pop('session', None) or requests.Session()
    s.headers.update({'User-Agent': UA})
    hdr = kw.pop('headers', {})
    kw.setdefault('timeout', 90)
    last = None
    for i in range(RETRIES_NET):
        try:
            return s.get(url, headers=hdr, **kw)
        except Exception as exc:
            last = exc
            log('net', 'retry %d %s: %s' % (i, type(exc).__name__, str(exc)[:70]))
            time.sleep(3 + i * 3)
    log('net', 'giving up: %s' % str(last)[:90])
    return None


def parse_signed(loc):
    """从签名 URL 解析 x-oss-date / x-oss-expires，算出剩余有效秒数。"""
    q = urllib.parse.parse_qs(urllib.parse.urlparse(loc).query)
    ds = (q.get('x-oss-date') or [None])[0]
    ex = (q.get('x-oss-expires') or [None])[0]
    if not ds or not ex:
        return None
    ds_clean = ds.rstrip('Z')
    sig = datetime.datetime.strptime(ds_clean, '%Y%m%dT%H%M%S')
    age = int((now_utc() - sig).total_seconds())
    return {'signed_at': ds_clean, 'expires_in': int(ex), 'age': age, 'ttl_left': int(ex) - age}


def resolve(short=KNOWN_SHORT, fname=KNOWN_FILE, verbose=True):
    """纯 HTTP：短链 302 -> 新鲜签名直链。不缓存签名（防 403）。"""
    url = SHORT_BASE + urllib.parse.quote(short) + '/' + urllib.parse.quote(fname)
    r = http_get(url, allow_redirects=False)
    if r is None:
        return {'ok': False, 'error': 'NETWORK_UNREACHABLE'}
    if r.status_code not in (301, 302, 303, 307, 308):
        return {'ok': False, 'error': 'HTTP_%d' % r.status_code, 'url': url}
    loc = r.headers.get('location') or ''
    if 'x-oss-signature' not in loc:
        return {'ok': False, 'error': 'NO_SIGNED_URL', 'location': loc[:200]}
    info = parse_signed(loc) or {}
    if verbose:
        log('resolve', 'signed_at=%s age=%ss ttl_left=%ss'
            % (info.get('signed_at'), info.get('age'), info.get('ttl_left')))
        log('resolve', 'host=%s path=%s' % (urllib.parse.urlparse(loc).netloc,
                                            urllib.parse.urlparse(loc).path[:70]))
    return {'ok': True, 'signed_url': loc, 'short': short, 'file': fname, 'sign': info}


def load_cookie(host='https://dl.aliceincradle.dev/'):
    """读取 Playwright 导出的 cf_clearance。"""
    if not os.path.exists(COOKIE_FILE):
        return {}
    try:
        ck = json.load(open(COOKIE_FILE))
    except Exception:
        return {}
    if isinstance(ck, dict):
        return {'Cookie': '; '.join('%s=%s' % (k, v) for k, v in ck.items())}
    return {'Cookie': '; '.join('%s=%s' % (c['name'], c['value']) for c in ck)}


def probe(signed):
    """探测签名直链：区分 真文件 / OSS过期403 / CF挑战页。"""
    r = http_get(signed, headers=load_cookie(), stream=True)
    if r is None:
        return {'kind': 'NETWORK_UNREACHABLE'}
    head = r.raw.read(24, decode_content=True) if r.ok else b''
    ct = r.headers.get('content-type', '')
    out = {'status': r.status_code, 'content_type': ct, 'magic': head[:8].hex()}
    if r.status_code in (401, 403):
        out['kind'] = 'LINK_EXPIRED_OR_FORBIDDEN'
    elif head.startswith(MAGIC_7Z):
        out['kind'] = 'OK_7Z'
    elif head.startswith(MAGIC_ZIP):
        out['kind'] = 'OK_ZIP'
    elif b'<html' in head.lower() or b'doctype' in head.lower():
        out['kind'] = 'CF_CHALLENGE_HTML'
    else:
        out['kind'] = 'UNKNOWN'
    r.close()
    return out


def download(signed, dest, total_limit=None):
    """下载并校验魔数；签名走一次性使用，不落缓存。"""
    r = http_get(signed, headers=load_cookie(), stream=True)
    if r is None:
        return {'ok': False, 'error': 'NETWORK_UNREACHABLE'}
    if r.status_code != 200:
        code = r.status_code
        r.close()
        return {'ok': False, 'error': 'HTTP_%d' % code,
                'fatal': code in (401, 403, 404)}
    tmp = dest + '.part'
    got = 0
    head = b''
    with open(tmp, 'wb') as f:
        for chunk in r.iter_content(1 << 16):
            if not chunk:
                continue
            if got == 0:
                head = chunk[:8]
            f.write(chunk)
            got += len(chunk)
            if total_limit and got > total_limit:
                break
    r.close()
    magic = head[:4]
    kind = '7z' if magic == bytes.fromhex('377a1a27') else ('zip' if head[:4] == MAGIC_ZIP else 'unknown')
    if kind == 'unknown':
        os.remove(tmp)
        return {'ok': False, 'error': 'NOT_ARCHIVE', 'magic': head[:8].hex(),
                'hint': 'CF_CHALLENGE_OR_HTML'}
    os.replace(tmp, dest)
    return {'ok': True, 'path': dest, 'bytes': got, 'kind': kind}


def capture(timeout_ms=120000):
    """Playwright 走年龄门并点击 Download，捕获短链/版本号（实测已验证可捕获）。"""
    try:
        from playwright.sync_api import sync_playwright
    except Exception:
        return {'ok': False, 'error': 'PLAYWRIGHT_MISSING'}
    hits = []
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--no-sandbox'])
        ctx = b.new_context(user_agent=UA, accept_downloads=True)
        pg = ctx.new_page()
        pg.on('request', lambda r: hits.append(r.url))
        ver = None
        ck = {}
        try:
            for attempt in range(3):
                try:
                    pg.goto(PAGE, wait_until='domcontentloaded', timeout=90000)
                    break
                except Exception as exc:
                    log('capture', 'goto retry %d %s' % (attempt, type(exc).__name__))
                    time.sleep(4)
            try:
                pg.wait_for_selector('#preloader', state='hidden', timeout=45000)
            except Exception:
                pass
            try:
                pg.click('text=Agree', timeout=12000)
            except Exception:
                pass
            for _ in range(20):
                body = pg.inner_text('body')
                if 'Current Version' in body and 'Loading' not in body:
                    break
                pg.wait_for_timeout(2000)
            ver = [l for l in pg.inner_text('body').split('\n') if 'Current Version' in l]
            for attempt in range(3):
                try:
                    pg.click("button:has-text('Download')", timeout=12000)
                except Exception:
                    pass
                pg.wait_for_timeout(10000)
                if any('vanilla.shiro.dev' in u for u in hits):
                    break
                log('capture', 'no shortlink yet, retry click %d' % attempt)
            ck = {c['name']: c['value'] for c in ctx.cookies()}
        finally:
            b.close()
    van = [u for u in hits if 'vanilla.shiro.dev' in u]
    val = [u for u in hits if '/verification/' in u]
    sig = [u for u in hits if 'x-oss-signature' in u]
    out = {'ok': bool(van), 'version': (ver[0] if ver else None),
           'vanilla': van[0] if van else None,
           'verification_seen': bool(val), 'signed_seen': bool(sig),
           'cookies': list(ck.keys())}
    if van:
        path = urllib.parse.urlparse(van[0]).path
        seg = [x for x in path.split('/') if x]
        if seg and seg[0] == 'f':
            seg = seg[1:]                      # 去掉固定前缀 /f/
        if len(seg) >= 2:
            out['short'], out['file'] = seg[0], urllib.parse.unquote(seg[-1])
        cache = {'short': out.get('short'), 'file': out.get('file'),
                 'version': out.get('version'), 'at': now_utc().isoformat()}
        json.dump(cache, open(SHORTLINK_CACHE, 'w'), ensure_ascii=False, indent=1)
        log('capture', '短码已缓存（非签名，可安全缓存）')
    if ck:
        json.dump(ck, open(COOKIE_FILE, 'w'))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('mode', choices=['resolve', 'probe', 'capture', 'download'])
    ap.add_argument('--short', default=None)
    ap.add_argument('--file', default=None)
    ap.add_argument('--twice', action='store_true')
    ap.add_argument('-o', '--out', default='aic.7z')
    a = ap.parse_args()
    short, fname = a.short, a.file
    if not short and os.path.exists(SHORTLINK_CACHE):
        c = json.load(open(SHORTLINK_CACHE))
        short, fname = c.get('short'), c.get('file')
    short = short or KNOWN_SHORT
    fname = fname or KNOWN_FILE
    log('target', 'short=%s file=%s' % (short, fname))

    if a.mode == 'capture':
        print(json.dumps(capture(), ensure_ascii=False, indent=1))
        return
    res = resolve(short, fname)
    if not res.get('ok'):
        print(json.dumps(res, ensure_ascii=False, indent=1)); sys.exit(1)
    if a.mode == 'resolve':
        if a.twice:
            time.sleep(2)
            r2 = resolve(short, fname, verbose=False)
            same = r2.get('signed_url') == res.get('signed_url')
            log('refresh', '两次签名相同=%s（False 表示短链会刷新签名）' % same)
        print(json.dumps({k: v for k, v in res.items() if k != 'signed_url'},
                         ensure_ascii=False, indent=1))
        log('url', res['signed_url'][:120] + '...')
        return
    if a.mode == 'probe':
        print(json.dumps(probe(res['signed_url']), ensure_ascii=False, indent=1)); return
    print(json.dumps(download(res['signed_url'], a.out), ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
