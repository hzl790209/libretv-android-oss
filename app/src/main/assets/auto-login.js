/*
 * 自动完成 LibreTV 的密码校验，避免「服务起来了但没登录 -> /api/search 401 -> 搜不到视频」。
 * 脚本里的密码占位符由 Java 端用 JSONObject.quote() 替换成安全的 JS 字符串字面量。
 * sessionStorage 标记保证登录成功后只 reload 一次，不会无限刷新。
 */
(function () {
  var P = __PASSWORD__;
  var KEY = '__ltv_auto_login';
  try {
    if (sessionStorage.getItem(KEY) === '1') return 'skip';
    fetch('/api/status', { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (s) {
        if (!s || !s.passwordRequired || s.verified) {
          sessionStorage.setItem(KEY, '1');   // 已登录或无需密码
          return;
        }
        return fetch('/api/auth', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ password: P })
        })
          .then(function (r) { return r.json(); })
          .then(function (d) {
            sessionStorage.setItem(KEY, '1');
            if (d && d.success) location.reload();
          });
      })
      .catch(function () { /* 服务未就绪时静默，下次页面加载再试 */ });
    return 'pending';
  } catch (e) {
    return 'err:' + (e && e.message ? e.message : String(e));
  }
})();
