"""Real RPTC runtime. Heavy dependencies and weights are loaded only on startup."""
import argparse
import hashlib
import math
from pathlib import Path
import threading
from types import SimpleNamespace

from .api import create_app


class RptcDetector:
    def __init__(self, model_path=None, threshold=0.5):
        if isinstance(threshold, bool) or not math.isfinite(threshold) or not 0 <= threshold <= 1:
            raise ValueError("threshold must be finite and between zero and one")
        # The existing preprocessing imports scipy/opencv/torchvision. Missing packages must
        # fail startup, rather than turning a model failure into an authentic-image verdict.
        import torch
        from networks import Net, initWeights
        from data.process import processing_RPTC

        weight_file = Path(model_path) if model_path else Path(__file__).resolve().parents[1] / "weights" / "RPTC.pth"
        if not weight_file.is_file():
            raise FileNotFoundError("RPTC weight file was not found")
        self.torch = torch
        self.preprocess = processing_RPTC
        self.threshold = float(threshold)
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        model = Net()
        model.apply(initWeights)
        # Only trusted local weights are loaded. Fail on unexpected architecture or parameters.
        state = torch.load(weight_file, map_location="cpu", weights_only=True)
        if not isinstance(state, dict):
            raise ValueError("Unsupported RPTC checkpoint structure")
        state = state.get("model", state.get("netC", state))
        if not isinstance(state, dict):
            raise ValueError("Unsupported RPTC state dictionary")
        state = {key.removeprefix("module."): value for key, value in state.items()}
        model.load_state_dict(state, strict=True)
        self.model = model.to(self.device).eval()
        self.lock = threading.Lock()
        with weight_file.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        self.model_version = "rptc-sha256-" + digest

    def __call__(self, image):
        torch = self.torch
        cuda_devices = [torch.cuda.current_device()] if self.device.type == "cuda" else []
        with self.lock, torch.inference_mode(), torch.random.fork_rng(devices=cuda_devices):
            # The original texture-block sampler is stochastic; constrain it per inference.
            torch.manual_seed(42)
            if self.device.type == "cuda":
                torch.cuda.manual_seed_all(42)
            tensor = self.preprocess(image, SimpleNamespace(patch_num=3, loadSize=256))
            output = self.model(tensor.unsqueeze(0).to(self.device)).sigmoid().flatten()
            if output.numel() != 1:
                raise ValueError("RPTC returned an unexpected prediction shape")
            score = float(output.item())
        if not math.isfinite(score) or not 0 <= score <= 1:
            raise ValueError("RPTC returned an invalid prediction")
        return {"score": score, "threshold": self.threshold, "modelVersion": self.model_version,
                "isAiGenerated": score > self.threshold}


def main():
    parser = argparse.ArgumentParser(description="Versioned local RPTC inference service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5001)
    parser.add_argument("--model-path", type=Path)
    parser.add_argument("--threshold", type=float, default=0.5)
    args = parser.parse_args()
    app = create_app(RptcDetector(args.model_path, args.threshold))
    # Development runner; production deployment must provide its own bounded worker setup.
    app.run(host=args.host, port=args.port, threaded=False, debug=False)


if __name__ == "__main__":
    main()
