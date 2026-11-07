# P4 图片推理协议入口

本目录新增 `/api/v1/detect` 文件上传协议，旧 `product.py` 的 `/detect` URL 接口与原模型文件不改动。新 Java 检测消费者只把服务器保存的图片字节作为 multipart 发送，不传用户 URL 或文件系统路径。

## 协议

`POST /api/v1/detect`，`multipart/form-data`，恰好一个 `image` 文件。内容必须能解码为 PNG/JPEG，文件不超过5MiB，图片不超过2000万像素。使用解码内容判断格式，不信任文件名和客户端 Content-Type；额外文件或 URL/path 表单字段会被拒绝。

成功200：

```json
{"schemaVersion":1,"score":0.75,"threshold":0.5,"modelVersion":"rptc-sha256-example","isAiGenerated":true}
```

score、threshold 是有限的0..1数字；isAiGenerated严格等于 `score > threshold`，等于阈值时为false。modelVersion限制ASCII `[A-Za-z0-9._:-]{1,128}`。模型分数不是数据集accuracy/AP，也不是图片真实性或交通违章的确定证明。

输入非法400，字节/像素超界413；检测器抛异常503，返回值不符合协议502。错误响应只返回通用错误，不泄露异常、路径、令牌或模型内部诊断。`X-Request-Id` 接受1..64位字母、数字、点、下划线、短横线，否则生成UUID，并在响应头返回。响应禁止缓存。

## P4 可重复协议测试

在本算法目录执行，解释器固定：

```powershell
& 'D:/soft/Anaconda/python' -m unittest discover -s tests -p test_service_api.py -v
```

Git Bash 对应 `/d/soft/Anaconda/python -m unittest discover -s tests -p test_service_api.py -v`。这些测试只注入检测桩，依赖 Flask/Pillow，不导入 torch、torchvision、cv2，不加载权重，不能作为模型准确率或真实权重联调证据。

## 真实运行入口（P8 验证）

```powershell
& 'D:/soft/Anaconda/python' -m service.runtime --host 127.0.0.1 --port 5001
```

启动才导入重依赖，使用原 `weights/RPTC.pth`、原 `processing_RPTC`。权重严格校验；优先使用可用CUDA，未检测到CUDA时使用CPU；推理互斥、固定种子、inference_mode。模型版本包含权重文件SHA256，可回溯具体权重。缺少依赖、权重不匹配或加载失败时直接启动失败，不伪造结果。

P8已在固定Anaconda3.12.7下加载原RPTC.pth完成CPU推理，Java端到端测试还实际验证了HTTP协议、模型重启恢复、独立复核和后续奖励链路。版本与权重摘要见`../../docs/test-reports/P8-model-summary.json`。程序化样本只证明运行与协议，不是有标注测试集准确率评估。

本机依赖隔离在`D:/Project/java/.cache/python-model`，没有替换Anaconda全局包。固定CPU组合为torch2.9.1+cpu、torchvision0.24.1+cpu、OpenCV4.11.0.86、NumPy1.26.4、SciPy1.13.1、Pillow10.4.0、Flask3.0.3；可复现约束见`requirements-cpu-windows.txt`。通过`../../traffic-platform/tools/start-model.ps1`启动，它设置当前进程的PYTHONPATH并调用同一个`D:/soft/Anaconda/python.exe`，只绑定127.0.0.1:5001。

Flask自带服务器用于本地联调；当前Docker演示通过host.docker.internal访问宿主机服务。正式网络部署另配有界worker与访问控制，当前项目没有交付边缘设备部署或真实支付。Java端已有HTTP硬超时、数据库任务租约、有限重试和严格响应校验。
