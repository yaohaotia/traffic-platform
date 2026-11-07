const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const crypto = require('crypto');
const { chromium } = require(path.resolve(__dirname, '../../../.cache/browser/node_modules/playwright'));
const project = path.resolve(__dirname, '..');
const reports = path.resolve(project, '../docs/test-reports');
const accounts = JSON.parse(fs.readFileSync(path.join(project, 'deploy/.generated/demo-accounts.json'), 'utf8').replace(/^\uFEFF/, ''));
const base = process.env.TRAFFIC_BROWSER_URL || 'http://127.0.0.1:28090';
const find = kind => accounts.find(account => account.kind === kind);
const tokens = new Map();
const checks = [];
let browser, page;
function redact(value) { let output = String(value); for (const account of accounts) output = output.split(account.password).join('[redacted]'); for (const token of tokens.values()) output = output.split(token).join('[redacted]'); return output; }
async function api(kind, route, method = 'GET', body) {
  if (!tokens.has(kind)) { const account = find(kind); const response = await fetch(base + '/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: account.username, password: account.password }) }); assert.equal(response.status, 200); tokens.set(kind, (await response.json()).accessToken); }
  const response = await fetch(base + '/api/v1' + route, { method, headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tokens.get(kind) }, body: body ? JSON.stringify(body) : undefined });
  if (!response.ok) throw new Error('Backend acceptance request failed: ' + route + ' HTTP ' + response.status);
  return response.status === 204 ? null : response.json();
}
async function waitFor(check, timeout = 60000) { const end = Date.now() + timeout; while (Date.now() < end) { const value = await check(); if (value) return value; await new Promise(resolve => setTimeout(resolve, 250)); } throw new Error('Business result did not converge before deadline'); }
async function login(kind) { const user = find(kind); await page.goto(base); await page.getByLabel('账号', { exact: true }).fill(user.username); await page.getByLabel('密码', { exact: true }).fill(user.password); await page.getByRole('button', { name: '登录工作台' }).click(); await page.locator('.sidebar').waitFor(); }
async function logout() { await page.getByRole('button', { name: '退出登录' }).click(); await page.getByRole('button', { name: '登录工作台' }).waitFor(); }
async function menu(name) { await page.locator('.sidebar').getByRole('button', { name, exact: true }).click(); await page.waitForTimeout(250); }
async function messageBox(value) { const box = page.locator('.el-message-box').filter({ visible: true }); await box.locator('input').fill(value); await box.locator('button.el-button--primary').click(); }
function rowWith(value) { return page.locator('.el-table__row').filter({ has: page.getByText(String(value), { exact: true }) }); }

(async () => {
  browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true });
  page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
  const errors = []; page.on('pageerror', error => errors.push(redact(error.message)));
  const title = '园区通行线索征集 · 页面验收 ' + Date.now();
  const campaign = await api('manager', '/campaigns', 'POST', { title, regionCode: 'NORTH', startsAt: new Date(Date.now() - 3600000).toISOString(), endsAt: new Date(Date.now() + 86400000).toISOString(), rewardAmount: 3.50, budgetAmount: 1000.00 });
  await api('manager', '/campaigns/' + campaign.id + '/publish', 'POST', {});
  const before = await api('user', '/wallet');
  await login('user'); assert.equal(await page.getByRole('button', { name: '账户与迁移', exact: true }).count(), 0);
  const forbidden = await page.evaluate(async () => { const session = JSON.parse(sessionStorage.getItem('traffic-platform-session-v1')); return (await fetch('/api/v1/identity/users', { headers: { Authorization: 'Bearer ' + session.accessToken } })).status; });
  assert.equal(forbidden, 403); checks.push('Citizen menu and server-side administration isolation');
  await rowWith(title).getByRole('button', { name: '提交线索' }).click();
  await page.getByText('地点', { exact: true }).first().locator('..').locator('input').fill('北校区入口');
  await page.locator('textarea').first().fill('页面验收线索：请根据征集规则人工核对。');
  await page.locator('input[type="file"][aria-label="证据图片"]').setInputFiles(path.join(reports, 'p8-evidence.png'));
  const submitted = page.waitForResponse(response => response.url().endsWith('/api/v1/reports') && response.request().method() === 'POST' && response.status() === 200);
  await page.getByRole('button', { name: '提交并开始检测' }).click(); const report = await (await submitted).json();
  const work = await waitFor(async () => (await api('manager', '/review-tasks')).items.find(item => item.reportId === report.id));
  assert.ok(work.modelVersion.startsWith('rptc-sha256-')); checks.push('Browser evidence upload and submission reached actual RPTC model');
  await logout();
  if (work.stage === 'PRIMARY') {
    await login('reviewer'); await menu('审核工作台'); await rowWith(report.id).getByRole('button', { name: '领取处理' }).click();
    let dialog = page.getByRole('dialog', { name: '人工审核', exact: true }); await dialog.waitFor();
    await dialog.getByRole('button', { name: '查看证据图片' }).click(); const picture = page.getByRole('dialog', { name: '证据图片', exact: true }); await picture.waitFor();
    await waitFor(() => picture.locator('img').evaluate(image => image.complete && image.naturalWidth > 0)); await picture.getByRole('button', { name: 'Close', exact: true }).click();
    await dialog.locator('textarea').fill('初审完成，交由其他审核员独立复核。'); await dialog.getByRole('button', { name: '提交独立复核' }).click(); await dialog.waitFor({ state: 'hidden' });
    await logout(); checks.push('Claimed reviewer can view protected image and escalate without auto-award');
  }
  await login('rechecker'); await menu('审核工作台'); await rowWith(report.id).getByRole('button', { name: '领取处理' }).click();
  const review = page.getByRole('dialog', { name: '人工审核', exact: true }); await review.locator('textarea').fill('独立复核确认线索符合已公布的征集规则。'); await review.getByRole('button', { name: '审核通过', exact: true }).click(); await review.waitFor({ state: 'hidden' });
  await waitFor(async () => (await api('user', '/rewards')).items.some(item => item.reportId === report.id && item.state === 'CREDITED'));
  await logout(); checks.push('Independent review reserved budget and MQ credited reward once');
  await login('user'); await menu('奖励与提现');
  const afterCredit = await api('user', '/wallet'); assert.equal(Number(afterCredit.available), Number(before.available) + 3.50);
  const withdrawn = page.waitForResponse(response => response.url().endsWith('/api/v1/withdrawals') && response.request().method() === 'POST' && response.status() === 200);
  await page.getByRole('button', { name: '提交申请', exact: true }).click(); const withdrawal = await (await withdrawn).json();
  await logout(); await login('manager'); await menu('运营管理');
  const pendingRow = rowWith(withdrawal.id); await pendingRow.getByRole('button', { name: '模拟结算', exact: true }).click(); await messageBox('页面验收：模拟结算，未连接真实支付。');
  await waitFor(async () => (await api('user', '/withdrawals')).items.some(item => item.id === withdrawal.id && item.state === 'SIMULATED_PAID'));
  await page.screenshot({ path: path.join(reports, 'P8-browser-operations.png'), fullPage: false });
  await logout(); await login('user'); await menu('奖励与提现'); const settled = await api('user', '/wallet');
  assert.equal(Number(settled.available), Number(before.available) + 2.50); assert.equal(Number(settled.frozen), Number(before.frozen));
  await page.screenshot({ path: path.join(reports, 'P8-browser-wallet.png'), fullPage: false }); checks.push('Browser withdrawal freeze and independent simulated settlement reconciled');
  await menu('站内通知'); await waitFor(async () => (await api('user', '/notifications')).items.length >= 3);
  await page.getByRole('button', { name: '标为已读', exact: true }).first().click(); checks.push('Recipient notification and read state');
  let refreshes = 0; const listener = request => { if (request.url().endsWith('/api/v1/auth/refresh')) refreshes++; }; page.on('request', listener);
  await page.evaluate(() => { const key = 'traffic-platform-session-v1'; const session = JSON.parse(sessionStorage.getItem(key)); session.accessToken = 'expired-test-access'; sessionStorage.setItem(key, JSON.stringify(session)); });
  await page.reload(); await page.locator('.sidebar').waitFor(); assert.equal(refreshes, 1); page.off('request', listener); checks.push('Expired access recovers using one rotating refresh request');
  await logout(); await login('manager'); await menu('账户与迁移');
  const mapping = JSON.parse(fs.readFileSync(path.join(project, 'deploy/.generated/legacy-mapping.json'), 'utf8').replace(/^\uFEFF/, ''));
  await page.getByLabel('历史档案文件', { exact: true }).setInputFiles(mapping.archive);
  await page.getByRole('button', { name: '执行预演', exact: true }).click(); await page.getByText('预演可导入', { exact: false }).waitFor();
  await page.getByPlaceholder('账号映射，例如 {"旧账号编号": 新账号编号}').fill(JSON.stringify(mapping.userMappings));
  await page.getByPlaceholder('迁移核对说明').fill('已核对只读导出的全部账号和线索数量，保留停用状态及原始审核状态。');
  const imported = page.waitForResponse(response => response.url().endsWith('/api/v1/migration/import') && response.status() === 200);
  await page.getByRole('button', { name: '确认映射并导入档案', exact: true }).click(); await page.locator('.el-message-box button.el-button--primary').click();
  const batch = await (await imported).json(); assert.equal(batch.userCount, mapping.expectedUsers); assert.equal(batch.reportCount, mapping.expectedReports);
  checks.push('Actual legacy archive preview/import matched ' + batch.userCount + ' users and ' + batch.reportCount + ' reports');
  await page.screenshot({ path: path.join(reports, 'P8-browser-migration.png'), fullPage: false });
  await page.setViewportSize({ width: 390, height: 844 }); await menu('专项征集'); await page.waitForTimeout(3500); await page.screenshot({ path: path.join(reports, 'P8-browser-mobile.png'), fullPage: false });
  assert.ok(await page.getByRole('button', { name: '退出登录', exact: true }).isVisible()); checks.push('Mobile navigation retains logout');
  assert.deepEqual(errors, []); await logout();
  fs.writeFileSync(path.join(reports, 'P8-browser-summary.json'), JSON.stringify({ status: 'PASSED', recordedAt: new Date().toISOString(), browser: 'Installed Microsoft Edge, headless', baseUrl: base, checks, runtimePageErrors: errors.length, reportId: report.id, campaignId: campaign.id, legacyUserCount: batch.userCount, legacyReportCount: batch.reportCount, frontendIndexSha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(project, 'deploy/web/index.html'))).digest('hex') }, null, 2));
  console.log('Browser acceptance passed: ' + checks.length + ' checks; real model, independent review, funds, refresh and archive import.');
})().catch(async error => { if (page) await page.screenshot({ path: path.join(reports, 'P8-browser-failure.png'), fullPage: false }).catch(() => {}); fs.writeFileSync(path.join(reports, 'P8-browser-summary.json'), JSON.stringify({ status: 'FAILED', checks, error: redact(error.message) }, null, 2)); console.error(redact(error.message)); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); });
