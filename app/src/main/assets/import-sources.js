/*
 * 把外部源配置合并进网页 localStorage。
 * 两个占位符由 Java 端以 JSONObject.quote() 转义后全量替换：
 * 原始文本，以及 merge / replace 模式字符串。占位符不得出现在注释里，
 * 否则用户导入的内容被替换进注释后有破坏语法的风险。
 *
 * 兼容四种格式：
 *   1. LibreTV 完整快照 {"state":{"customAPIs":[...],...},"version":8}
 *   2. LibreTV-SourceList / 导出文件 {"sources":[...],"liveSources":[...],"subscriptions":[...]}
 *   3. TVBOX 配置 {"sites":[{"name","api"}],"lives":[...]}
 *   4. 裸数组 [{name,url}] 或 ["https://..."]
 * 只接受 http/https 地址；按 key 幂等去重，同址别名保留。
 */
(function () {
  var RAW = __RAW__;
  var MODE = __MODE__;
  var KEY = 'libretv-settings';

  /** 稳定短哈希：无 key 的条目按 名称+地址 生成，保证跨文件重复导入幂等且不互相撞 key */
  function hash(s) {
    var h = 5381;
    for (var i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
    return (h >>> 0).toString(36);
  }

  function normApi(x, i) {
    if (!x) return null;
    var url = (typeof x === 'string') ? x : (x.url || x.api || x.playUrl || '');
    if (typeof url !== 'string' || !/^https?:\/\//i.test(url.trim())) return null;
    var obj = (x && typeof x === 'object') ? x : {};
    var name = String(obj.name || obj.title || url).trim() || url;
    var u = url.trim().replace(/\/+$/, '');
    var item = {
      name: name,
      url: u,
      key: String(obj.key || ('import_' + hash(name + '|' + u))),
      isAdult: obj.isAdult === true
    };
    if (obj.detail) item.detail = String(obj.detail);
    return item;
  }

  function normList(x) {
    if (!Array.isArray(x)) return [];
    return x.filter(function (e) {
      if (typeof e === 'string') return /^https?:\/\//i.test(e);
      return e && (e.url || e.api);
    }).map(function (e) {
      if (typeof e === 'string') {
        var u = e.replace(/\/+$/, '');
        return { name: u, url: u, key: 'import_live_' + hash(u) };
      }
      var copy = {};
      for (var k in e) if (Object.prototype.hasOwnProperty.call(e, k)) copy[k] = e[k];
      if (!copy.url && copy.api) copy.url = copy.api;
      if (!copy.key) copy.key = 'import_live_' + hash(String(copy.url || copy.name || ''));
      return copy;
    });
  }

  function keyOf(x) {
    if (!x) return '';
    if (typeof x === 'string') return 'u:' + x;
    return x.key ? 'k:' + String(x.key) : 'u:' + String(x.url || '');
  }

  function mergeList(current, incoming) {
    var out = (MODE === 'replace') ? [] : (Array.isArray(current) ? current.slice() : []);
    var seen = {};
    out.forEach(function (x) { seen[keyOf(x)] = 1; });
    var added = 0;
    (incoming || []).forEach(function (x) {
      var k = keyOf(x);
      if (k && !seen[k]) { out.push(x); seen[k] = 1; added++; }
    });
    return { list: out, added: added };
  }

  try {
    var p = JSON.parse(RAW);
    var apis = [], lives = [], subs = [], selected = [];
    if (p && p.state && Array.isArray(p.state.customAPIs)) {
      apis = p.state.customAPIs.map(normApi);
      lives = normList(p.state.liveSubscriptions);
      subs = normList(p.state.subscriptions);
      selected = p.state.selectedKeys || [];
    } else if (Array.isArray(p)) {
      apis = p.map(normApi);
    } else if (p && (p.sources || p.liveSources || p.sites || p.lives)) {
      apis = (p.sources || p.sites || []).map(normApi);
      lives = normList(p.liveSources || p.lives);
      subs = normList(p.subscriptions);
      selected = p.selectedKeys || [];
    } else {
      return 'err:无法识别的源配置格式';
    }
    apis = apis.filter(Boolean);
    if (!apis.length && !lives.length && !subs.length) return 'err:文件里没有可用的源';

    var cur = null;
    try { cur = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { cur = null; }
    var cs = (MODE === 'replace') ? {} : ((cur && cur.state) || {});

    var r1 = mergeList(cs.customAPIs, apis);
    cs.customAPIs = r1.list;
    var r2 = mergeList(cs.liveSubscriptions, lives);
    cs.liveSubscriptions = r2.list;
    var r3 = mergeList(cs.subscriptions, subs);
    cs.subscriptions = r3.list;

    var sk = (MODE === 'replace') ? [] : (Array.isArray(cs.selectedKeys) ? cs.selectedKeys.slice() : []);
    var sset = {};
    sk.forEach(function (k) { sset[k] = 1; });
    var addedKeys = 0;
    apis.forEach(function (a) { if (!sset[a.key]) { sk.push(a.key); sset[a.key] = 1; addedKeys++; } });
    if (MODE === 'replace') {
      (Array.isArray(selected) ? selected : []).forEach(function (k) {
        if (!sset[k]) { sk.push(k); sset[k] = 1; addedKeys++; }
      });
    }
    cs.selectedKeys = sk;

    var out = { state: cs, version: (cur && cur.version) || (p && p.version) || 8 };
    localStorage.setItem(KEY, JSON.stringify(out));
    // ok:<新增点播>:<点播总数>:<新增直播/订阅>，分别报告避免把直播数混进点播数
    return 'ok:' + r1.added + ':' + cs.customAPIs.length + ':' + (r2.added + r3.added);
  } catch (e) {
    return 'err:' + (e && e.message ? e.message : String(e));
  }
})();
