"""P4 protocol tests only: no model imports, weight loading, or accuracy assertions."""
from io import BytesIO
import math
import struct
import sys
import unittest
import uuid
import zlib

from PIL import Image
from service.api import MAX_IMAGE_BYTES, create_app


def image_bytes(format_name="PNG", mode="RGB"):
    content = BytesIO()
    with Image.new(mode, (8, 6)) as image:
        image.save(content, format=format_name)
    return content.getvalue()


def png_with_oversized_dimensions():
    # A valid PNG IHDR with recomputed CRC. It must be rejected before pixel allocation.
    content = bytearray(image_bytes())
    content[16:24] = struct.pack(">II", 5001, 4000)
    content[29:33] = struct.pack(">I", zlib.crc32(content[12:29]) & 0xFFFFFFFF)
    return bytes(content)


class ServiceProtocolTest(unittest.TestCase):
    def setUp(self):
        self.seen = []
        self.result = {"score": 0.75, "threshold": 0.5,
                       "modelVersion": "rptc-test-v1", "isAiGenerated": True}

        def detector(image):
            self.seen.append((image.mode, image.size))
            return dict(self.result)

        self.app = create_app(detector)
        self.app.config.update(TESTING=True)
        self.client = self.app.test_client()

    def upload(self, content=None, filename="image.png", client=None, **kwargs):
        client = client or self.client
        response = client.post("/api/v1/detect", data={"image": (BytesIO(content if content is not None else image_bytes()), filename)},
                               **kwargs)
        # Werkzeug's multipart builder may spill its synthetic request body to a temp file.
        self.addCleanup(response.request.input_stream.close)
        self.addCleanup(response.close)
        return response

    def assert_error(self, response, status, code):
        self.addCleanup(response.request.input_stream.close)
        self.addCleanup(response.close)
        self.assertEqual(status, response.status_code, response.get_data(as_text=True))
        body = response.get_json()
        self.assertEqual(1, body["schemaVersion"])
        self.assertEqual(code, body["error"]["code"])
        self.assertEqual(response.headers["X-Request-Id"], body["requestId"])
        self.assertEqual("no-store", response.headers["Cache-Control"])

    def test_valid_png_contract_and_request_id(self):
        response = self.upload(headers={"X-Request-Id": "detect-request-123"})
        self.assertEqual(200, response.status_code)
        self.assertEqual({"schemaVersion": 1, **self.result}, response.get_json())
        self.assertEqual("detect-request-123", response.headers["X-Request-Id"])
        self.assertEqual([("RGB", (8, 6))], self.seen)

    def test_valid_jpeg_uses_decoded_content_not_filename(self):
        response = self.upload(image_bytes("JPEG"), filename="../../untrusted-name.bin")
        self.assertEqual(200, response.status_code)
        self.assertEqual([("RGB", (8, 6))], self.seen)

    def test_rgba_png_is_converted_to_rgb(self):
        self.assertEqual(200, self.upload(image_bytes(mode="RGBA")).status_code)
        self.assertEqual("RGB", self.seen[0][0])

    def test_missing_file_rejected_without_detector_call(self):
        response = self.client.post("/api/v1/detect", data={}, content_type="multipart/form-data")
        self.assert_error(response, 400, "INVALID_UPLOAD")
        self.assertEqual([], self.seen)

    def test_empty_file_rejected(self):
        self.assert_error(self.upload(b""), 400, "INVALID_IMAGE")
        self.assertEqual([], self.seen)

    def test_forged_image_bytes_are_rejected(self):
        self.assert_error(self.upload(b"This is not a PNG", filename="image.png"), 400, "INVALID_IMAGE")
        self.assertEqual([], self.seen)

    def test_gif_content_is_rejected(self):
        self.assert_error(self.upload(image_bytes("GIF")), 400, "INVALID_IMAGE")

    def test_truncated_png_is_rejected(self):
        self.assert_error(self.upload(image_bytes()[:35]), 400, "INVALID_IMAGE")

    def test_file_byte_limit_is_independent_of_multipart_envelope(self):
        self.assert_error(self.upload(b"x" * (MAX_IMAGE_BYTES + 1)), 413, "IMAGE_TOO_LARGE")
        self.assertEqual([], self.seen)

    def test_whole_request_limit_has_json_error(self):
        response = self.client.post("/api/v1/detect", data=b"x" * (MAX_IMAGE_BYTES + 65537),
                                    content_type="multipart/form-data; boundary=a")
        self.assert_error(response, 413, "IMAGE_TOO_LARGE")

    def test_pixel_limit_rejected_before_decode_or_detector(self):
        self.assert_error(self.upload(png_with_oversized_dimensions()), 413, "IMAGE_TOO_LARGE")
        self.assertEqual([], self.seen)

    def test_duplicate_image_parts_are_rejected(self):
        from werkzeug.datastructures import MultiDict
        response = self.client.post("/api/v1/detect", data=MultiDict([
            ("image", (BytesIO(image_bytes()), "one.png")),
            ("image", (BytesIO(image_bytes()), "two.png"))]))
        self.assert_error(response, 400, "INVALID_UPLOAD")

    def test_extra_file_parts_are_rejected(self):
        response = self.client.post("/api/v1/detect", data={
            "image": (BytesIO(image_bytes()), "one.png"),
            "other": (BytesIO(image_bytes()), "two.png")})
        self.assert_error(response, 400, "INVALID_UPLOAD")

    def test_user_url_and_path_are_never_accepted(self):
        for field, value in [("file", "http://127.0.0.1/private"), ("path", "C:/private.png")]:
            with self.subTest(field=field):
                response = self.client.post("/api/v1/detect", data={
                    "image": (BytesIO(image_bytes()), "one.png"), field: value})
                self.assert_error(response, 400, "INVALID_UPLOAD")
        self.assertEqual([], self.seen)

    def test_json_url_request_is_rejected(self):
        response = self.client.post("/api/v1/detect", json={"file": "http://localhost/private"})
        self.assert_error(response, 400, "INVALID_UPLOAD")

    def test_invalid_request_id_replaced_and_not_logged(self):
        for request_id in ["untrusted request id", "a" * 65, ""]:
            with self.subTest(request_id=request_id):
                response = self.upload(headers={"X-Request-Id": request_id})
                self.assertEqual(200, response.status_code)
                self.assertEqual(response.headers["X-Request-Id"], str(uuid.UUID(response.headers["X-Request-Id"])))

    def test_model_exception_returns_generic_503_without_diagnostics(self):
        def broken(_image):
            raise RuntimeError("private/path/password should not appear")
        client = create_app(broken).test_client()
        with self.assertLogs("service.api", level="ERROR") as logs:
            response = self.upload(client=client, headers={"X-Request-Id": "runtime-1"})
        self.assert_error(response, 503, "DETECTOR_UNAVAILABLE")
        self.assertNotIn("private/path/password", response.get_data(as_text=True))
        self.assertNotIn("private/path/password", "\n".join(logs.output))
        self.assertIn("runtime-1", "\n".join(logs.output))

    def test_score_at_threshold_has_false_verdict(self):
        self.result.update(score=0.5, threshold=0.5, isAiGenerated=False)
        self.assertEqual(200, self.upload().status_code)

    def test_zero_and_one_scores_are_valid(self):
        for score, threshold, verdict in [(0.0, 0.0, False), (1.0, 0.0, True), (1.0, 1.0, False)]:
            with self.subTest(score=score, threshold=threshold):
                self.result.update(score=score, threshold=threshold, isAiGenerated=verdict)
                self.assertEqual(200, self.upload().status_code)

    def test_invalid_scores_and_thresholds_are_rejected(self):
        for field in ["score", "threshold"]:
            for value in [math.nan, math.inf, -math.inf, -0.01, 1.01, True, "0.5", None, 10**1000]:
                with self.subTest(field=field, value_type=type(value).__name__):
                    self.result = {"score": 0.75, "threshold": 0.5, "modelVersion": "rptc-test-v1", "isAiGenerated": True}
                    self.result[field] = value
                    with self.assertLogs("service.api", level="ERROR"):
                        self.assert_error(self.upload(), 502, "INVALID_MODEL_RESULT")

    def test_invalid_verdict_and_version_are_rejected(self):
        for field, value in [("isAiGenerated", False), ("isAiGenerated", 1), ("isAiGenerated", "true"),
                             ("isAiGenerated", None), ("modelVersion", ""), ("modelVersion", "a" * 129),
                             ("modelVersion", "private/path"), ("modelVersion", "v1\ninjected"), ("modelVersion", "v1+dev")]:
            with self.subTest(field=field, value=value):
                self.result = {"score": 0.75, "threshold": 0.5, "modelVersion": "rptc-test-v1", "isAiGenerated": True}
                self.result[field] = value
                with self.assertLogs("service.api", level="ERROR"):
                    self.assert_error(self.upload(), 502, "INVALID_MODEL_RESULT")

    def test_missing_fields_and_non_mapping_result_are_rejected(self):
        for result in [None, 0.7, [], {}, {"score": 0.7}, {"accuracy": 0.99}]:
            with self.subTest(result=result):
                client = create_app(lambda _image, result=result: result).test_client()
                with self.assertLogs("service.api", level="ERROR"):
                    self.assert_error(self.upload(client=client), 502, "INVALID_MODEL_RESULT")

    def test_model_extra_fields_do_not_leak_into_protocol(self):
        self.result["privateDiagnostics"] = "must not be returned"
        response = self.upload()
        self.assertEqual(200, response.status_code)
        self.assertNotIn("privateDiagnostics", response.get_json())

    def test_importing_runtime_does_not_import_heavy_modules(self):
        prior = set(sys.modules)
        from service.runtime import RptcDetector
        self.assertTrue(callable(RptcDetector))
        for module in ["torch", "torchvision", "cv2"]:
            if module not in prior:
                self.assertNotIn(module, sys.modules)

    def test_create_app_requires_callable_detector(self):
        with self.assertRaises(TypeError):
            create_app(None)


if __name__ == "__main__":
    unittest.main()
