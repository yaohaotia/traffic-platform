"""Small, testable HTTP boundary with no torch/torchvision/cv2 import dependency."""
from collections.abc import Mapping
from io import BytesIO
import math
import re
import uuid
import warnings

from flask import Flask, g, jsonify, request
from PIL import Image, UnidentifiedImageError
from werkzeug.exceptions import BadRequest, RequestEntityTooLarge


MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_IMAGE_PIXELS = 20_000_000
_REQUEST_ID = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")
_MODEL_VERSION = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")


class _InputError(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message


def _image_from_upload():
    # Only file bytes are accepted. Never dereference an uploaded URL or local path.
    if request.mimetype != "multipart/form-data":
        raise _InputError(400, "INVALID_UPLOAD", "Expected one multipart image file")
    if request.form or set(request.files) != {"image"} or len(request.files.getlist("image")) != 1:
        raise _InputError(400, "INVALID_UPLOAD", "Expected one multipart image file")
    uploaded = request.files["image"]
    try:
        content = uploaded.stream.read(MAX_IMAGE_BYTES + 1)
    finally:
        # Large multipart files may spool to disk; close promptly on rejected inputs too.
        uploaded.close()
    if len(content) > MAX_IMAGE_BYTES:
        raise _InputError(413, "IMAGE_TOO_LARGE", "Image exceeds the supported byte limit")
    if not content:
        raise _InputError(400, "INVALID_IMAGE", "Image must be a valid PNG or JPEG")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(content)) as source:
                if source.format not in {"PNG", "JPEG"}:
                    raise _InputError(400, "INVALID_IMAGE", "Image must be a valid PNG or JPEG")
                if source.width <= 0 or source.height <= 0 or source.width * source.height > MAX_IMAGE_PIXELS:
                    raise _InputError(413, "IMAGE_TOO_LARGE", "Image exceeds the supported pixel limit")
                source.load()
                return source.convert("RGB")
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise _InputError(413, "IMAGE_TOO_LARGE", "Image exceeds the supported pixel limit") from None
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError):
        raise _InputError(400, "INVALID_IMAGE", "Image must be a valid PNG or JPEG") from None


def _validated_result(result):
    if not isinstance(result, Mapping):
        return None
    score, threshold = result.get("score"), result.get("threshold")
    for number in (score, threshold):
        if isinstance(number, bool) or not isinstance(number, (int, float)):
            return None
        try:
            if not math.isfinite(number) or not 0 <= number <= 1:
                return None
        except (OverflowError, TypeError, ValueError):
            return None
    version, verdict = result.get("modelVersion"), result.get("isAiGenerated")
    if not isinstance(version, str) or not _MODEL_VERSION.fullmatch(version):
        return None
    if type(verdict) is not bool or verdict != (score > threshold):
        return None
    # Ignore all unrecognised model fields; never expose arbitrary backend diagnostics.
    return {"schemaVersion": 1, "score": float(score), "threshold": float(threshold),
            "modelVersion": version, "isAiGenerated": verdict}


def create_app(detector):
    """Create an app for callable(PIL.Image RGB) -> score/threshold/version/verdict mapping.

    Loading, locking, timeouts and inference are the runtime detector's responsibilities.
    The HTTP layer never falls back to a fabricated prediction.
    """
    if not callable(detector):
        raise TypeError("detector must be callable")
    app = Flask(__name__)
    # Permit multipart envelope overhead while enforcing the file limit independently.
    app.config.update(MAX_CONTENT_LENGTH=MAX_IMAGE_BYTES + 64 * 1024)

    @app.before_request
    def request_id():
        supplied = request.headers.get("X-Request-Id", "")
        g.request_id = supplied if _REQUEST_ID.fullmatch(supplied) else str(uuid.uuid4())

    @app.after_request
    def response_id(response):
        response.headers["X-Request-Id"] = getattr(g, "request_id", str(uuid.uuid4()))
        response.headers["Cache-Control"] = "no-store"
        return response

    def error(status, code, message):
        return jsonify(schemaVersion=1, error={"code": code, "message": message},
                       requestId=getattr(g, "request_id", "")), status

    @app.errorhandler(RequestEntityTooLarge)
    def oversized(_exception):
        return error(413, "IMAGE_TOO_LARGE", "Upload exceeds the supported byte limit")

    @app.errorhandler(BadRequest)
    def malformed(_exception):
        return error(400, "INVALID_UPLOAD", "Malformed multipart upload")

    @app.post("/api/v1/detect")
    def detect():
        try:
            image = _image_from_upload()
        except _InputError as invalid:
            return error(invalid.status, invalid.code, invalid.message)
        try:
            result = detector(image)
        except Exception:
            # Do not log exception text, request content, tokens, filenames, or untrusted headers.
            app.logger.error("Detector failed requestId=%s", g.request_id)
            return error(503, "DETECTOR_UNAVAILABLE", "Image detector is temporarily unavailable")
        finally:
            image.close()
        try:
            valid = _validated_result(result)
        except Exception:
            valid = None
        if valid is None:
            app.logger.error("Detector returned an invalid result requestId=%s", g.request_id)
            return error(502, "INVALID_MODEL_RESULT", "Image detector returned an invalid result")
        return jsonify(valid)

    return app
