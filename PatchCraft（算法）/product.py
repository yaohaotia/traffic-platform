"""单张图片的 AI 合成风险检测；分数为模型输出，不是数据集准确率。"""
import os
import math
import threading
from io import BytesIO
from types import SimpleNamespace
from urllib.parse import urlparse

import requests
import torch
from flask import Flask, request, jsonify
from PIL import Image
from networks import Net, initWeights
from data.process import processing_RPTC

app = Flask(__name__)
DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
MODEL_PATH = os.path.join(os.path.dirname(__file__), 'weights', 'RPTC.pth')
ALLOWED_HOSTS = set(os.environ.get('IMAGE_ALLOWED_HOSTS', 'sw8bce0d5.hb-bkt.clouddn.com').split(','))
MAX_BYTES = 5 * 1024 * 1024
model = Net()
model.apply(initWeights)
state = torch.load(MODEL_PATH, map_location='cpu')
state = state.get('model', state.get('netC', state))
state = {k.removeprefix('module.'): v for k, v in state.items()}
model.load_state_dict(state, strict=True)
model.to(DEVICE).eval()
inference_lock = threading.Lock()


@app.route('/detect', methods=['POST'])
def detect_ai_image():
    url = request.form.get('file', '')
    parsed = urlparse(url)
    if parsed.scheme not in ('http', 'https') or parsed.hostname not in ALLOWED_HOSTS or parsed.username or parsed.password:
        return jsonify(error='Image URL is not allowed'), 400
    try:
        # 禁止跳转，下载大小和时间均有边界。
        with requests.get(url, stream=True, timeout=(3, 10), allow_redirects=False) as response:
            if response.status_code != 200:
                return jsonify(error='Image download failed'), 502
            content = bytearray()
            for chunk in response.iter_content(65536):
                content.extend(chunk)
                if len(content) > MAX_BYTES:
                    return jsonify(error='Image is too large'), 413
        with Image.open(BytesIO(content)) as source:
            if source.width * source.height > 20000000:
                return jsonify(error='Image dimensions are too large'), 413
            image = source.convert('RGB')
        with inference_lock, torch.inference_mode():
            # 复用仓库中的纹理块处理，使用固定随机种子保证重复检测一致。
            with torch.random.fork_rng(devices=[torch.cuda.current_device()] if DEVICE.type == 'cuda' else []):
                torch.manual_seed(42)
                tensor = processing_RPTC(image, SimpleNamespace(patch_num=3, loadSize=256))
                score = float(model(tensor.unsqueeze(0).to(DEVICE)).sigmoid().flatten().item())
        if not math.isfinite(score):
            raise ValueError('Invalid model output')
        return jsonify(is_ai_generated=score > 0.5, confidence=score, method_used='RPTC')
    except (requests.RequestException, OSError, ValueError):
        app.logger.exception('Image detection failed')
        return jsonify(error='Image detection failed'), 502


if __name__ == '__main__':
    app.run(host='127.0.0.1', port=5000, threaded=False)
