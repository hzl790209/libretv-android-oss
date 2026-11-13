/*
 * 导出网页里当前的源配置。
 * 输出同时包含标准 LibreTV-SourceList 字段和完整 state 快照，
 * 既方便人工编辑 / 分享，也能原样导回来（含勾选状态与别名）。
 */
(function () {
  try {
    var s = JSON.parse(localStorage.getItem('libretv-settings') || 'null');
    var st = (s && s.state) || {};
    var apis = Array.isArray(st.customAPIs) ? st.customAPIs : [];
    var payload = {
      format: 'LibreTV-SourceList',
      generator: 'LibreTV Android 1.0.0',
      exportedAt: new Date().toISOString(),
      sources: apis.map(function (a) {
        var o = { name: a.name || a.url, url: a.url, key: a.key, isAdult: a.isAdult === true };
        if (a.detail) o.detail = a.detail;
        return o;
      }),
      liveSources: Array.isArray(st.liveSubscriptions) ? st.liveSubscriptions : [],
      subscriptions: Array.isArray(st.subscriptions) ? st.subscriptions : [],
      selectedKeys: Array.isArray(st.selectedKeys) ? st.selectedKeys : [],
      version: (s && s.version) || 8,
      state: st
    };
    return JSON.stringify(payload);
  } catch (e) {
    return JSON.stringify({ error: String(e && e.message ? e.message : e) });
  }
})();
