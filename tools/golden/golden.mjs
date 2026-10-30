// 用 Playwright 驱动 SillyTavern，让它在 chat-completion=custom 下请求本地 mock，
// mock 把 ST 真实发出的 messages 落盘为 capture.json —— 这就是 golden case 的 ground truth。
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';

const [, , chromePath, cardPath, fileType, capturePath, userText, cardName] = process.argv;
const BASE = 'http://127.0.0.1:8000';
const MOCK = process.env.MOCK_URL || 'http://127.0.0.1:9100/v1';

fs.rmSync(capturePath, { force: true });

const browser = await chromium.launch({ executablePath: chromePath, headless: true });
const page = await browser.newPage();

page.on('console', (msg) => {
    if (msg.type() === 'error') console.error('[page error]', msg.text());
});

await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 180_000 });
await page.waitForSelector('#send_textarea', { timeout: 180_000 });

// CSRF token（ST 的写接口要求 X-CSRF-Token）
const csrf = await page.evaluate(async () => {
    const r = await fetch('/csrf-token');
    const j = await r.json();
    return j.token;
});

const fileName = path.basename(cardPath);
const importRes = await page.request.post(`${BASE}/api/characters/import`, {
    headers: { 'X-CSRF-Token': csrf },
    multipart: {
        avatar: { name: fileName, mimeType: 'application/octet-stream', buffer: fs.readFileSync(cardPath) },
        file_type: fileType,
    },
});
console.log('import status', importRes.status(), await importRes.text());

await page.reload({ waitUntil: 'domcontentloaded' });
await page.waitForSelector('#send_textarea', { timeout: 180_000 });
await page.waitForFunction(() => {
    const c = window.SillyTavern?.getContext?.();
    return !!c && Array.isArray(c.characters) && c.characters.length > 0 && !!c.chatCompletionSettings;
}, undefined, { timeout: 180_000 });

const selected = await page.evaluate(async (matchName) => {
    const ctx = window.SillyTavern.getContext();
    let idx = -1;
    const want = new RegExp(matchName, 'i');
    ctx.characters.forEach((c, nameIdx) => { if (want.test(c.name ?? '')) idx = nameIdx; });
    if (idx < 0) return { ok: false, names: ctx.characters.map((c) => c.name) };
    await ctx.selectCharacterById(idx);
    await new Promise((r) => setTimeout(r, 1500));
    await ctx.executeSlashCommandsWithOptions('/newchat');
    await new Promise((r) => setTimeout(r, 1200));
    return { ok: true, idx, name: ctx.characters[idx]?.name, chid: ctx.this_chid, chatLen: ctx.chat?.length };
}, cardName || 'Seraphina');
console.log('select', JSON.stringify(selected));

const applied = await page.evaluate((mock) => {
    const fire = (el, ev) => el && el.dispatchEvent(new Event(ev, { bubbles: true }));
    const main = document.querySelector('#main_api');
    if (main) { main.value = 'openai'; fire(main, 'change'); }
    const srcSel = document.querySelector('#chat_completion_source');
    if (srcSel) { srcSel.value = 'custom'; fire(srcSel, 'change'); }
    const urlEl = document.querySelector('#custom_api_url_text');
    if (urlEl) { urlEl.value = mock; fire(urlEl, 'input'); fire(urlEl, 'change'); }
    const modelEl = document.querySelector('#custom_model_id');
    if (modelEl) { modelEl.value = 'mock-model'; fire(modelEl, 'input'); fire(modelEl, 'change'); }
    const ctx = window.SillyTavern.getContext();
    const s = ctx.chatCompletionSettings;
    s.chat_completion_source = 'custom';
    s.custom_url = mock;
    s.custom_model = 'mock-model';
    s.custom_prompt_post_processing = 'none';
    s.stream_openai = false;
    return {
        source: s.chat_completion_source,
        url: s.custom_url,
        model: s.custom_model,
        preset: s.preset_settings_openai,
        prompts: Array.isArray(s.prompts) ? s.prompts.length : -1,
    };
}, MOCK);
console.log('settings', JSON.stringify(applied));

await page.evaluate((text) => {
    const el = document.querySelector('#send_textarea');
    el.value = text;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    document.querySelector('#send_but').click();
}, userText || 'Hello there, tell me about tea.');

const deadline = Date.now() + 120_000;
while (Date.now() < deadline && !fs.existsSync(capturePath)) {
    await new Promise((r) => setTimeout(r, 500));
}
const ok = fs.existsSync(capturePath);
console.log('capture exists', ok, capturePath);
if (ok) {
    const json = JSON.parse(fs.readFileSync(capturePath, 'utf8'));
    console.log('messages', json.messages?.length, 'model', json.model, 'stream', json.stream);
}
await browser.close();
process.exit(ok ? 0 : 2);
