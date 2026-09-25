/* 车机桌面 v0.1.7 数据链自检：
 * mock CarBridge 桥，验证 onMedia / onWeather / onGpsSpeed / onGpsStatus / onAlt
 * 都能正确改到界面，且桥接管后演示歌词轮播停止。
 * 跑法：NODE_PATH=<workspace>/node_modules node test-feeds.js  （工作目录随意，读绝对路径） */
const { chromium } = require('playwright-core');
const path = require('path');

(async () => {
  const htmlPath = 'S:/AI智能体/WorkBuddy/2026-09-25-21-16-21/car-desktop/index.html';
  const exe = 'C:/Users/Administrator/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe';

  const browser = await chromium.launch({ executablePath: exe, headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--force-device-scale-factor=1'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  // 网页一加载就有 CarBridge（模拟安卓壳注入的桥）
  await page.addInitScript(() => {
    const store = {};
    window.CarBridge = {
      getPref: k => store[k] || '',
      setPref: (k, v) => { store[k] = v; },
      mediaPerm: () => true,
      openMediaPerm: () => {},
      startFeeds: () => {},
      media: () => {}, setVolume: () => '8/15', getVolume: () => '8/15',
      openApp: () => true, hasApp: () => true, listApps: () => '[]',
      mapWindow: () => {}, toast: () => {}
    };
  });
  page.on('pageerror', e => { console.log('PAGE ERROR: ' + e.message); process.exitCode = 1; });
  await page.goto('file:///' + encodeURI(htmlPath.replace(/\\/g, '/')));
  await page.waitForTimeout(1200);

  const t = (name, ok) => { console.log((ok ? 'PASS' : 'FAIL') + '  ' + name); if (!ok) process.exitCode = 1; };

  // 1) 初始：演示态（假歌名在）
  t('初始演示歌名', (await page.textContent('#mtitle')).includes('2002'));

  // 2) 推真实媒体 → 音乐卡换真歌名，歌词轮播停
  await page.evaluate(() => {
    window.onMedia({ ok: 1, t: '西海情歌', a: '刀郎', app: '酷我音乐', p: true });
  });
  await page.waitForTimeout(100);
  t('onMedia 换真歌名', (await page.textContent('#mtitle')).includes('西海情歌'));
  t('顶栏显示来源', (await page.textContent('#conn')).includes('酷我音乐'));
  const lyricBefore = await page.textContent('#lyric2');
  await page.waitForTimeout(4600);
  t('桥接管后歌词轮播停止', (await page.textContent('#lyric2')) === lyricBefore);

  // 3) 未在播放状态
  await page.evaluate(() => { window.onMedia({ ok: 0 }); });
  t('onMedia ok=0 显示未在播放', (await page.textContent('#mtitle')).includes('未在播放'));

  // 4) 天气真值
  await page.evaluate(() => { window.onWeather({ ok: 1, t: 25, h: 60, d: '西北', s: 12 }); });
  t('onWeather 温度=25', (await page.textContent('#wTemp')) === '25');
  t('onWeather 风向=西北12', (await page.textContent('#wDir')) === '西北12');
  await page.waitForTimeout(300); // 演示刷新是 30s，不会覆盖；再等一小会儿确认
  t('真天气不被演示覆盖', (await page.textContent('#wTemp')) === '25');

  // 5) GPS 车速 + 海拔 + 状态
  await page.evaluate(() => { window.onGpsStatus('wait'); });
  t('onGpsStatus wait 提示', (await page.textContent('#spdHint')).includes('GPS'));
  await page.evaluate(() => { window.onAlt(66); window.onGpsSpeed(88); });
  t('onGpsSpeed 车速=88', (await page.textContent('#spdNum')).trim() === '88');
  t('onGpsSpeed 海拔=66', (await page.textContent('#wAlt')).trim() === '66');
  t('车速卡提示 GPS 实时', (await page.textContent('#spdHint')).includes('GPS 实时'));

  // 6) 设置面板授权按钮（openPanel 在闭包里，点真实按钮打开）
  await page.click('#setBtn');
  t('面板打开后授权按钮=已授权', (await page.textContent('#btnMediaPerm')).includes('已授权'));

  await page.screenshot({ path: 'S:/AI智能体/WorkBuddy/2026-09-25-21-16-21/car-desktop/test-feeds.png' });
  await browser.close();
  console.log('done');
})();
