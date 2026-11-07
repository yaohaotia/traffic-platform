"""Real-weight smoke evidence, deliberately not an accuracy benchmark."""
import importlib.metadata
import json
from pathlib import Path
import sys
import time
from PIL import Image

project = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(project.parent / 'PatchCraft（算法）'))
from service.runtime import RptcDetector

started = time.monotonic()
detector = RptcDetector()
images = [Image.new('RGB', (512, 512), (120, 135, 145)), Image.new('RGB', (256, 256), (60, 90, 120))]
images[0].save(project.parent / 'docs/test-reports/p8-evidence.png')
results = [detector(image) for image in images]
repeat = detector(images[0])
assert results[0]['score'] == repeat['score']
for result in results:
    assert 0 <= result['score'] <= 1
    assert result['isAiGenerated'] == (result['score'] > result['threshold'])
summary = {'status': 'PASSED', 'interpreter': sys.executable, 'python': sys.version.split()[0],
           'versions': {name: importlib.metadata.version(name) for name in ['torch', 'torchvision', 'opencv-python-headless', 'numpy', 'scipy', 'Pillow', 'Flask']},
           'device': str(detector.device), 'modelVersion': detector.model_version, 'proceduralSamples': results,
           'deterministicRepeat': True, 'elapsedSeconds': round(time.monotonic() - started, 3),
           'accuracyEvaluation': 'NOT_PERFORMED: these procedural fixtures have no authenticity ground-truth labels'}
report = project.parent / 'docs/test-reports/P8-model-summary.json'
report.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'status': summary['status'], 'device': summary['device'], 'deterministicRepeat': True, 'report': str(report)}, ensure_ascii=False))
