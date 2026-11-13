/*
 * 在 LibreTV 页面里把 APK 内置的源配置合并进 localStorage。
 * PRESET 占位符会被 Java 端替换成一个已正确转义的 JSON 字符串字面量。
 * 只合并源列表，尽量不覆盖用户在手机上已有的偏好设置。
 */
(function () {
  function mergeList(current, preset, keyOf) {
    var out = Array.isArray(current) ? current.slice() : [];
    var seen = {};
    out.forEach(function (x) { seen[keyOf(x)] = 1; });
    var added = 0;
    (Array.isArray(preset) ? preset : []).forEach(function (x) {
      var k = keyOf(x);
      if (k && !seen[k]) {
        out.push(x);
        seen[k] = 1;
        added++;
      }
    });
    return { list: out, added: added };
  }

  try {
    var KEY = 'libretv-settings';
    var preset = JSON.parse(__PRESET__);
    var ps = preset.state || {};
    var cur = null;
    try { cur = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { cur = null; }

    var added = 0;
    /* 除「新增源」之外是否还有别的变更（勾选列表补齐 / 偏好补齐）。
       全部为 0 时说明这条配置早就合并过了，直接返回、不写盘。 */
    var changed = 0;
    if (!cur || !cur.state) {
      cur = { state: ps, version: preset.version };
      added = (ps.customAPIs || []).length;
      changed = 1;
    } else {
      var cs = cur.state || {};
      // 必须按 key 去重：电脑界面里允许同一个 URL 有多条别名（47 条源只有 43 个不同地址），
      // 早期版本按 URL 去重会永久丢掉 4 条，而且补不回来。
      var keyOfApi = function (x) {
        if (!x) return '';
        return x.key ? 'k:' + String(x.key)
                     : (x.url ? 'u:' + String(x.url) : '');
      };
      var keyOfSub = function (x) {
        if (x && typeof x === 'object') {
          return x.key ? 'k:' + String(x.key) : 'u:' + String(x.url || '');
        }
        return 'u:' + String(x || '');
      };

      var r1 = mergeList(cs.customAPIs, ps.customAPIs, keyOfApi);
      cs.customAPIs = r1.list;
      added += r1.added;

      var r2 = mergeList(cs.liveSubscriptions, ps.liveSubscriptions, keyOfSub);
      cs.liveSubscriptions = r2.list;
      added += r2.added;

      var r3 = mergeList(cs.subscriptions, ps.subscriptions, keyOfSub);
      cs.subscriptions = r3.list;
      added += r3.added;

      // 勾选列表取并集：内置源默认全部启用
      var sk = Array.isArray(cs.selectedKeys) ? cs.selectedKeys.slice() : [];
      var sset = {};
      sk.forEach(function (k) { sset[k] = 1; });
      (ps.selectedKeys || []).forEach(function (k) {
        if (!sset[k]) { sk.push(k); sset[k] = 1; changed++; }
      });
      cs.selectedKeys = sk;

      // 手机端还没设置过的偏好，用电脑上的值补齐
      ['qualityMap', 'yellowFilter', 'recommendSource', 'recommendSourceTouched',
       'sourceHealth', 'liveSelectedUrls', 'liveRecent'].forEach(function (k) {
        if (cs[k] === undefined && ps[k] !== undefined) { cs[k] = ps[k]; changed++; }
      });
      cur.state = cs;
      if (!cur.version) { cur.version = preset.version; }
    }

    /* 没有任何新增就不要再写回。localStorage.setItem 是同步落盘，
       内置源 45KB，每次页面导航都重写一遍会让加载明显变慢，
       播放页来回切换时尤其明显（日志里「页面加载完成」动辄连续两三条）。 */
    if (added === 0 && changed === 0) return 'skip';
    localStorage.setItem(KEY, JSON.stringify(cur));
    return 'ok:' + added;
  } catch (e) {
    return 'err:' + (e && e.message ? e.message : String(e));
  }
})();
