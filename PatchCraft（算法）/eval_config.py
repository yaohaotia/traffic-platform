#貌似没用，其实有用，eval_all 用了from eval_config import *，所以也拥有了from util import mkdir
from util import mkdir


# root to the testsets
dataroot = r'D:\Project\AIGenerate\AIGCDetectionBenchMark\PatchCraft\api_temp' # change this to your testset path


# list of synthesis algorithms
print(dataroot)
# vals = ['progan', 'stylegan', 'biggan', 'cyclegan', 'stargan', 'gaugan',
#         'stylegan2', 'whichfaceisreal',
#         'ADM','Glide','Midjourney','stable_diffusion_v_1_4','stable_diffusion_v_1_5','VQDM','wukong','DALLE2']
vals = ['bird']


