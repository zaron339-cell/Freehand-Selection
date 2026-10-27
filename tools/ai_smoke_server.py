"""Local UI fixture only. Never calls a model or forwards screenshots elsewhere."""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    requests = []

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(self.requests).encode())

    def log_message(self, *_):
        pass

    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= 4_000_000:
            self.send_error(413)
            return
        body = json.loads(self.rfile.read(size))
        self.requests.append(body)
        text = (
            "### 本地模拟接口测试\n\n"
            "**未调用真实 AI**。已收到选区与提问；这里只检查显示与交互。\n\n"
            "这是一句包含**重要观点**与*补充说明*的回答。\n\n"
            "- 文科示例：结合时代背景理解一段史料。\n"
            "- 理科示例：直角边 3 和 4 对应斜边 5。\n\n"
            "> 你可以继续追问，不需要一次讲完所有内容。\n\n"
            "| 形式 | 显示 |\n| --- | --- |\n| 加粗 | **重点** |\n| 代码 | `**代码**` |\n\n"
            "代码里的标记应保持原样：`**代码**`。"
        )
        stream = body.get("stream", True)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8" if stream else "application/json; charset=utf-8")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            if stream:
                for start in range(0, len(text), 8):
                    chunk = {"choices": [{"delta": {"content": text[start:start + 8]}}]}
                    self.wfile.write(("data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n").encode())
                    self.wfile.flush()
                    time.sleep(0.08)
                self.wfile.write(b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
            else:
                self.wfile.write(json.dumps({"choices": [{"message": {"content": text}, "finish_reason": "stop"}]}, ensure_ascii=False).encode())
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8769)
    args = parser.parse_args()
    print(f"Local fixture only: http://127.0.0.1:{args.port}/v1", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
