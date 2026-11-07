'''
python eval_all.py --model_path ./weights/{}.pth --detect_method {CNNSpot,Gram,Fusing,FreDect,LGrad,LNP,DIRE}  --noise_type {blur,jpg,resize}
'''

import os
import csv
import shutil

import requests
from flask import Flask, request, jsonify
from numpy import mean
import torch

from PIL import ImageFile
import uuid
from util import  set_random_seed

#network有_init_.py，证明是一个python包
from networks import Net as RPTC
from networks import initWeights

from validate import validate

from options import TestOptions

from eval_config import *


#是为了允许 PIL 加载不完整或损坏的图像文件
ImageFile.LOAD_TRUNCATED_IMAGES = True


# 固定随机种子，确保在使用随机数时每次运行结果都一致，固定整个程序中所有可能的随机因素，以便于实验的可复现性。
set_random_seed()
# Running tests


opt = TestOptions().parse(print_options=True)

opt.batch_size = 1
opt.no_flip = True
opt.no_crop = True
opt.no_resize = True


app = Flask(__name__)



#model_name = os.path.basename(opt.model_path).replace('.pth', '')



# rows = [["{} model testing on...".format(model_name)],
#         ['testset', 'accuracy', 'avg precision']]
#
# print("{} model testing on...".format(model_name))


@app.route('/detect', methods=['POST'])
def detect_ai_imagedetect_ai_image():
    url = request.form.get('file')
    if not url:
        return jsonify({'error': 'No URL provided'}), 400

    session_id = str(uuid.uuid4())
    temp_root = os.path.join(dataroot, session_id)

    # 创建 real 和 fake 文件夹
    real_dir = os.path.join(temp_root, '0_real')
    fake_dir = os.path.join(temp_root, '1_fake')
    os.makedirs(real_dir, exist_ok=True)
    os.makedirs(fake_dir, exist_ok=True)

    try:
        response = requests.get(url)
        response.raise_for_status()


        save_path = os.path.join(real_dir, '0.jpg')
        with open(save_path, "wb") as f:
            f.write(response.content)




        opt.dataroot = '{}/{}'.format(dataroot,session_id )


        model = RPTC()
        model.apply(initWeights)
        state_dict = torch.load(opt.model_path, map_location='cpu')

        try:
            model.load_state_dict(state_dict['netC'],strict=True)
        except:
            model.load_state_dict({k.replace('module.', ''): v for k, v in state_dict['netC'].items()})

        model.cuda()
        model.eval()


        acc, _, _, _, _, _ = validate(model, opt)

        return jsonify({'accuracy': float(acc)})
    except requests.RequestException as e:
        return jsonify({'error': f'Failed to download image: {str(e)}'}), 400
    except Exception as e:
        return jsonify({'error': str(e)}), 500
    finally:
        if os.path.exists(temp_root):
            shutil.rmtree(temp_root, ignore_errors=True)



if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
# 结果文件
# csv_name = opt.results_dir + '/{}.csv'.format(opt.noise_type)
# with open(csv_name, 'a+') as f:
#     csv_writer = csv.writer(f, delimiter=',')
#     csv_writer.writerows(rows)


