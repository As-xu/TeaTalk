// 本地 mock OpenAI 端点：只记录请求体，用于抓 SillyTavern 真实发出的 prompt。
// 用法：node mock-openai.mjs <port> <capture-file>
import http from 'node:http';
import fs from 'node:fs';

const port = Number(process.argv[2] || 9100);
const captureFile = process.argv[3] || 'capture.json';

const server = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url.includes('/models')) {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ data: [{ id: 'mock-model', object: 'model' }] }));
        return;
    }
    if (req.method === 'POST' && req.url.includes('/chat/completions')) {
        let body = '';
        req.on('data', (c) => (body += c));
        req.on('end', () => {
            try {
                const json = JSON.parse(body);
                fs.writeFileSync(captureFile, JSON.stringify(json, null, 2));
            } catch {
                fs.writeFileSync(captureFile, body);
            }
            const stream = body.includes('"stream":true') || body.includes('"stream": true');
            const reply = 'MOCK-REPLY';
            if (stream) {
                res.writeHead(200, { 'content-type': 'text/event-stream' });
                const chunk = {
                    id: 'chatcmpl-mock',
                    object: 'chat.completion.chunk',
                    created: Math.floor(Date.now() / 1000),
                    model: 'mock-model',
                    choices: [{ index: 0, delta: { role: 'assistant', content: reply }, finish_reason: null }],
                };
                res.write(`data: ${JSON.stringify(chunk)}\n\n`);
                res.write('data: [DONE]\n\n');
                res.end();
            } else {
                res.writeHead(200, { 'content-type': 'application/json' });
                res.end(JSON.stringify({
                    id: 'chatcmpl-mock',
                    object: 'chat.completion',
                    created: Math.floor(Date.now() / 1000),
                    model: 'mock-model',
                    choices: [{ index: 0, message: { role: 'assistant', content: reply }, finish_reason: 'stop' }],
                    usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
                }));
            }
        });
        return;
    }
    res.writeHead(404);
    res.end();
});

server.listen(port, '127.0.0.1', () => {
    console.log(`mock-openai listening on http://127.0.0.1:${port}, capture -> ${captureFile}`);
});
