/*
 * Node 进程启动引导层，通过 NODE_OPTIONS="--require <this file>" 加载。
 *
 * 为什么需要它：手机上「批量测活全部失败」时，网页只能看到一句「失败」，
 * 真正的错误发生在 Node 进程内部（DNS / TLS / undici 连接），桌面端不会复现。
 * 这一层做三件事：
 *   1) 自愈：Termux 版 OpenSSL 的 CA 目录编译成 /data/data/com.termux/...（安卓上不存在），
 *      由 Java 侧注入 NODE_EXTRA_CA_CERTS；这里再给 dns.lookup 加 c-ares 兜底，
 *      避免 getaddrinfo 在安卓子进程失败时 SSRF Agent 的 safeLookup 让每个连接全灭。
 *   2) 取证：包装 globalThis.fetch，把每一次出站失败的完整错误链
 *      （name / message / code / cause...）落盘，不再依赖用户导出日志猜。
 *   3) 体检：服务起来后自动跑 DNS / HTTPS / fetch 对照，产出中文结论。
 *
 * 全部逻辑包在 try/catch 里，任何异常都不得阻断 LibreTV 启动。
 */
(function () {
  'use strict';

  // node --version 自检进程不做任何探测，避免污染报告
  if (process.argv && process.argv.indexOf('--version') >= 0) return;

  var fs, dns, dnsP, https, tls, urlMod;
  try {
    fs = require('fs');
    dns = require('dns');
    dnsP = require('dns/promises');
    https = require('https');
    tls = require('tls');
    urlMod = require('url');
  } catch (e) { return; }

  var OUT = process.env.LTV_PROBE_OUT || '';
  var PROBE_URL = process.env.LTV_PROBE_URL || 'https://www.baidu.com';
  var CA_FILE = process.env.NODE_EXTRA_CA_CERTS || '';

  var R = {
    generatedAt: new Date().toISOString(),
    node: process.version,
    platform: process.platform + '/' + process.arch,
    openssl: process.versions && process.versions.openssl,
    uvThreadpool: process.env.UV_THREADPOOL_SIZE || '(default 4)',
    dnsResultOrder: process.env.NODE_OPTIONS || '',
    ca: {
      extraFile: CA_FILE,
      fileReadable: false,
      bundledRootCertCount: -1,
      systemCertDir: '/system/etc/security/cacerts'
    },
    dns: { servers: [], setServersError: '', lookupOk: false, fallbackUsed: 0, errors: {} },
    probe: {},
    fetchErrors: [],
    conclusion: ['体检进行中，请在服务启动约 12 秒后再打开本页']
  };

  try { R.ca.bundledRootCertCount = (tls.rootCertificates || []).length; } catch (e) { }
  try { R.ca.fileReadable = !!(CA_FILE && fs.existsSync(CA_FILE) && fs.statSync(CA_FILE).size > 1000); } catch (e) { }
  try { R.ca.systemCertDirExists = fs.existsSync(R.ca.systemCertDir); } catch (e) { }
  try { R.ca.resolvConfExists = fs.existsSync('/etc/resolv.conf'); } catch (e) { }

  function save() {
    if (!OUT) return;
    try { fs.writeFileSync(OUT, JSON.stringify(R, null, 2)); } catch (e) { }
  }

  function describe(e) {
    if (!e) return { name: 'unknown', message: String(e) };
    var o = {
      name: e.name || '',
      message: e.message || String(e),
      code: e.code || ''
    };
    var chain = [], c = e.cause, i = 0;
    while (c && i++ < 4) {
      chain.push({ name: c.name || '', message: c.message || '', code: c.code || '' });
      c = c.cause;
    }
    if (chain.length) o.cause = chain;
    return o;
  }

  function shortErr(d) {
    // probe 结果统一是 { ok, error } 包装，业务错误则是裸 describe() 结构
    if (d && d.error) d = d.error;
    if (!d) return '未知错误';
    var parts = [];
    if (d.code) parts.push(d.code);
    if (d.name && d.name !== 'Error') parts.push(d.name);
    if (d.message) parts.push(d.message);
    var cur = d;
    var guard = 0;
    while (cur && cur.cause && cur.cause.length && guard++ < 3) {
      var c = cur.cause[0];
      if (c.code) parts.push('<- ' + c.code);
      if (c.message && parts.join(' ').indexOf(c.message) < 0) parts.push('<- ' + c.message);
      cur = c;
    }
    return parts.join(' · ').slice(0, 320);
  }

  // ---------------------------------------------------------------- 1) DNS 自愈
  var servers = (process.env.LTV_DNS_SERVERS || '')
    .split(',').map(function (s) { return s.trim(); })
    .filter(function (s) { return /^\d+\.\d+\.\d+\.\d+$/.test(s); });
  if (!servers.length) servers = ['8.8.8.8', '223.5.5.5'];
  R.dns.servers = servers;
  try {
    dns.setServers(servers);
    dnsP.setServers(servers);
  } catch (e) {
    R.dns.setServersError = shortErr(describe(e));
  }

  function caresResolve(host) {
    return dnsP.resolve4(host).then(function (v4) {
      return (v4 || []).map(function (a) { return { address: a, family: 4 }; });
    });
  }

  function noteDns(kind, host, err) {
    var d = describe(err);
    var k = d.code || d.name || 'unknown';
    if (!R.dns.errors[k]) R.dns.errors[k] = { count: 0, kind: kind, host: host, example: shortErr(d) };
    R.dns.errors[k].count++;
    save();
  }

  // callback 版：ssrf.ts 的 safeLookup 用的是 node:dns 的 lookup
  var rawLookup = dns.lookup;
  if (typeof rawLookup === 'function') {
    dns.lookup = function (host, opts, cb) {
      if (typeof opts === 'function') { cb = opts; opts = {}; }
      if (typeof cb !== 'function') return rawLookup.apply(dns, arguments);
      opts = opts || {};
      var wantAll = !!opts.all;
      return rawLookup.call(dns, host, opts, function (err, a, b) {
        if (!err) { R.dns.lookupOk = true; return cb(null, a, b); }
        noteDns('lookup', host, err);
        caresResolve(host).then(function (list) {
          if (!list.length) return cb(err, a, b);
          R.dns.fallbackUsed++;
          save();
          if (wantAll) cb(null, list);
          else cb(null, list[0].address, list[0].family);
        }, function () { cb(err, a, b); });
      });
    };
  }

  // promises 版：ssrf.ts 的 isBlockedByDNS 用的是 node:dns/promises 的 lookup
  var rawLookupP = dnsP.lookup;
  if (typeof rawLookupP === 'function') {
    dnsP.lookup = function (host, opts) {
      return rawLookupP.call(dnsP, host, opts).catch(function (err) {
        noteDns('lookup(promises)', host, err);
        return caresResolve(host).then(function (list) {
          if (!list.length) throw err;
          R.dns.fallbackUsed++;
          save();
          if (opts && opts.all) return list;
          return { address: list[0].address, family: 4 };
        });
      });
    };
  }
  save();

  // ---------------------------------------------------------------- 2) 出站错误取证
  var rawFetch = globalThis.fetch;
  if (typeof rawFetch === 'function') {
    globalThis.fetch = function (input, init) {
      var u;
      try {
        u = typeof input === 'string' ? input
          : (input && input.url) ? input.url : String(input);
      } catch (e) { u = '(unknown)'; }
      var p;
      try { p = rawFetch.apply(this, arguments); }
      catch (e) { noteFetch(u, e); throw e; }
      return Promise.resolve(p).catch(function (e) { noteFetch(u, e); throw e; });
    };
  }

  var seenFetch = {};
  function noteFetch(u, e) {
    var d = describe(e);
    var key = (d.code || '') + '|' + (d.name || '') + '|' + (d.message || '').slice(0, 80);
    if (seenFetch[key]) { seenFetch[key].count++; save(); return; }
    var entry = { count: 1, url: String(u).slice(0, 180), at: new Date().toISOString(), error: d };
    seenFetch[key] = entry;
    R.fetchErrors.push(entry);
    if (R.fetchErrors.length > 12) R.fetchErrors.shift();
    save();
  }

  // ---------------------------------------------------------------- 3) 启动后体检
  function probeHost() {
    try { return new urlMod.URL(PROBE_URL).hostname; } catch (e) { return 'www.baidu.com'; }
  }

  function conclude(host) {
    var c = [];
    var codes = Object.keys(R.dns.errors);
    var dnsOk = R.probe.lookup && R.probe.lookup.ok;
    if (!dnsOk) {
      c.push('DNS 系统解析失败' + (codes.length ? '（' + codes.join(' / ') + '）' : ''));
      if (R.probe.cares && R.probe.cares.ok) {
        c.push('c-ares 兜底可用，已自动接管 dns.lookup；若测活仍全失败，问题在连接或 TLS 层');
      } else {
        c.push('c-ares 也不可用：Node 完全没有可用 DNS，所有源必然失败');
      }
    } else {
      c.push('DNS 正常（' + host + ' → ' + (R.probe.lookup.addrs || []).slice(0, 2)
        .map(function (x) { return x.address || x; }).join(', ') + '）');
    }
    if (R.probe.https && R.probe.https.ok) {
      c.push('HTTPS 正常（HTTP ' + R.probe.https.status + '，证书链可信）');
    } else if (R.probe.https) {
      c.push('HTTPS 失败：' + shortErr(R.probe.https) + ' → 通常是 CA / TLS 问题');
    }
    if (R.probe.fetch && R.probe.fetch.ok) {
      c.push('fetch 直连正常（HTTP ' + R.probe.fetch.status + '）');
    } else if (R.probe.fetch) {
      c.push('fetch 直连失败：' + shortErr(R.probe.fetch));
    }
    if (R.ca.fileReadable) {
      c.push('CA 已注入（内置 ' + R.ca.bundledRootCertCount + ' 张 + 外部证书包）');
    } else {
      c.push('警告：外部 CA 证书包不可读，仅靠 Node 内置 ' + R.ca.bundledRootCertCount + ' 张根证书');
    }
    if (R.fetchErrors.length) {
      c.push('');
      c.push('实际业务请求捕获到的错误（这就是测活失败原因）：');
      R.fetchErrors.slice(-6).forEach(function (f) {
        c.push('  ×' + f.count + ' ' + shortErr(f.error) + '  [' + f.url.slice(0, 90) + ']');
      });
    } else {
      c.push('');
      c.push('暂无业务请求错误：请回到页面点一次「批量测活」，再打开本页刷新结论');
    }
    if (R.dns.fallbackUsed) c.push('DNS 兜底已被调用 ' + R.dns.fallbackUsed + ' 次');
    R.conclusion = c;
    R.generatedAt = new Date().toISOString();
    save();
    try { console.log('[LTV-PROBE]\n' + c.join('\n')); } catch (e) { }
  }

  setTimeout(function () {
    var host = probeHost();
    try {
      dns.lookup(host, { all: true }, function (err, addrs) {
        R.probe.lookup = err ? { ok: false, code: err.code || '', message: err.message || '' }
          : { ok: true, addrs: (addrs || []).slice(0, 4) };
        save();
      });
    } catch (e) { R.probe.lookup = { ok: false, message: String(e) }; }

    dnsP.resolve4(host).then(function (v4) {
      R.probe.cares = { ok: true, addrs: (v4 || []).slice(0, 4) };
    }, function (e) {
      R.probe.cares = { ok: false, error: describe(e) };
    }).then(save);

    try {
      var req = https.get(PROBE_URL, { timeout: 9000 }, function (res) {
        R.probe.https = { ok: true, status: res.statusCode };
        res.resume();
        save();
      });
      req.on('timeout', function () { req.destroy(new Error('https timeout')); });
      req.on('error', function (e) {
        R.probe.https = { ok: false, error: describe(e) };
        save();
      });
    } catch (e) { R.probe.https = { ok: false, error: describe(e) }; }

    try {
      var target = PROBE_URL + (PROBE_URL.indexOf('?') >= 0 ? '&' : '?')
        + 'ac=videolist&wd=test&pg=1';
      var signal = (typeof AbortSignal !== 'undefined' && AbortSignal.timeout)
        ? AbortSignal.timeout(12000) : undefined;
      globalThis.fetch(target, signal ? { signal: signal } : {}).then(function (r) {
        R.probe.fetch = { ok: true, status: r.status };
        try { r.body && r.body.cancel && r.body.cancel(); } catch (e) { }
        save();
      }, function (e) {
        R.probe.fetch = { ok: false, error: describe(e) };
        save();
      });
    } catch (e) { R.probe.fetch = { ok: false, error: describe(e) }; }

    setTimeout(function () { conclude(host); }, 9500);
  }, 3000);

  save();
})();
