/*
 * 网络与搜索链路自检：一次把「服务是否就绪 / 是否登录 / 有没有源 /
 * 服务端能否出网 / 手机网络能否到源站」五件事查清楚。
 *
 * 关键对照：同一个源，分别由 ①Node 服务端测活、②WebView 浏览器直连。
 * 浏览器通而服务端不通 => 程序/运行时问题（DNS、SSRF、超时）；
 * 两边都不通 => 手机网络或源站问题。
 * 结果经 LtvBridge.onDiagnosis() 回传原生对话框，同时写进应用日志。
 */
(function () {
  var report = function (t) {
    try { LtvBridge.onDiagnosis(String(t)); } catch (e) { }
  };
  var lines = [];
  var t0 = Date.now();
  function push(s) { lines.push(s); }
  function finish() { report(lines.join('\n')); }
  function errText(e) { return e && e.message ? e.message : String(e); }

  function fetchT(url, opts, ms) {
    opts = opts || {};
    var ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    if (ctl) opts.signal = ctl.signal;
    var timer = setTimeout(function () { if (ctl) ctl.abort(); }, ms || 12000);
    return fetch(url, opts).then(function (r) { clearTimeout(timer); return r; },
      function (e) { clearTimeout(timer); throw e; });
  }

  function safeJson(r) {
    return r.json().then(function (d) { return { code: r.status, d: d }; },
      function () { return { code: r.status, d: {} }; });
  }

  fetchT('/api/status', { credentials: 'same-origin' }, 8000)
    .then(safeJson)
    .then(function (x) {
      var s = x.d || {}, srcs = s.defaultSources || [];
      push('【服务】/api/status ' + x.code + ' · ' + (s.verified ? '已登录' : '未登录')
        + ' · 服务端预置源 ' + srcs.length + ' 条');
      if (!srcs.length) {
        var n = 0;
        try {
          var q = JSON.parse(localStorage.getItem('libretv-settings') || 'null');
          n = q && q.state && Array.isArray(q.state.customAPIs) ? q.state.customAPIs.length : 0;
        } catch (e) { }
        push('【源】网页本地源 ' + n + ' 条；服务端没有预置源，请配置 DEFAULT_SOURCES');
        finish();
        return null;
      }
      var use = srcs.slice(0, 8);
      var first = use[0];

      var pSearch = fetchT('/api/search', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ wd: '三体', sources: use, filterAdult: true })
      }, 32000).then(safeJson).then(function (y) {
        var d = y.d || {}, list = d.list || [], fail = d.failures || [];
        var msg = '【服务端搜索】HTTP ' + y.code + ' · 结果 ' + list.length + ' 条 · 成功 '
          + (use.length - fail.length) + '/' + use.length + ' 源';
        if (list.length) msg += ' · 示例「' + (list[0].name || '') + '」';
        var errs = {};
        fail.forEach(function (f) { var k = f.error || '未知'; errs[k] = (errs[k] || 0) + 1; });
        var top = Object.keys(errs).sort(function (a, b) { return errs[b] - errs[a]; }).slice(0, 2);
        if (top.length) msg += '\n  失败：' + top.map(function (k) {
          return k + ' ×' + errs[k];
        }).join('；');
        return msg;
      }).catch(function (e) { return '【服务端搜索】请求异常：' + errText(e); });

      // 测 3 个源并列：全灭时若错误码一致，就能直接判定是运行时问题而非源站问题
      var pTest = Promise.all(use.slice(0, 3).map(function (s) {
        return fetchT('/api/source/test', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: s.url })
        }, 22000).then(safeJson).then(function (y) {
          var d = y.d || {};
          return '【服务端测活】' + s.name + '：HTTP ' + y.code + ' · '
            + (d.ok ? '成功，' + d.ms + 'ms / ' + d.count + ' 条'
                    : '失败：' + (d.error || d.message || '未知'));
        }).catch(function (e) {
          return '【服务端测活】' + s.name + ' 异常：' + errText(e);
        });
      })).then(function (a) { return a.join('\n'); });

      // no-cors 得到 opaque 响应即代表网络可达，不受 CORS 限制
      var pBrowser = fetchT(first.url + '?ac=videolist&wd=test&pg=1',
        { mode: 'no-cors', credentials: 'omit' }, 10000)
        .then(function () { return true; }, function () { return false; });

      return Promise.all([pSearch, pTest, pBrowser]).then(function (r) {
        push(r[0]);
        push(r[1]);
        push('【网页直连对照】' + (r[2] ? '可达（手机网络到该源通）'
                                        : '不可达（手机网络本身到不了该源）'));
        var serverBad = r[1].indexOf('失败') >= 0 || r[1].indexOf('异常') >= 0;
        push('');
        if (x.code === 401) {
          push('=> 未登录（401）：服务端已设访问密码。请在页面输入密码，');
          push('   或在设置里把「访问密码」清空并重启服务（开源版默认为空，清空即免登录）。');
        } else if (!serverBad) {
          push('=> 服务端出站正常；个别源失败是源站自身问题');
        } else if (r[2]) {
          push('=> 手机网络可达，但 Node 服务出站失败（DNS / 证书 / SSRF / 超时）。');
          push('   请点「运行环境体检」查看 Node 真实错误码，复制那段文字发回即可精准定位。');
        } else {
          push('=> 手机网络到该源不可达（运营商/地区限制，或该源需要代理）。');
          push('   可在设置里关掉「手机本地服务」，改连电脑上的 LibreTV 服务。');
        }
        push('\n用时 ' + ((Date.now() - t0) / 1000).toFixed(1) + 's');
        finish();
      });
    })
    .catch(function (e) {
      push('自检失败：' + errText(e));
      push('服务可能未就绪，或手机无法访问该地址。');
      finish();
    });
  return 'started';
})();
