#这是一个解析命名行参数的类


import argparse
import os
import util
import torch
#import models
#import data


class TestOptions():

    #创建类实例时， 会自动执行 __init__() 方法
    def __init__(self):
        self.initialized = False

    def initialize(self, parser):

        
        # data augmentation
        parser.add_argument('--rz_interp', default='bilinear')
        parser.add_argument('--blur_sig', default='1.0')
        parser.add_argument('--jpg_method', default='pil')
        parser.add_argument('--jpg_qual', default='95')

        parser.add_argument('--batch_size', type=int, default=64, help='input batch size')
        parser.add_argument('--loadSize', type=int, default=256, help='scale images to this size')
        parser.add_argument('--CropSize', type=int, default=224, help='scale images to this size')
        parser.add_argument('--no_crop', action='store_true')
        parser.add_argument('--no_resize', action='store_true')
        parser.add_argument('--no_flip', action='store_true', help='if specified, do not flip the images for data augmentation')

        parser.add_argument('--model_path',type=str,default='./weights/RPTC.pth',help='the path of detection model')
        #训练时候增加泛化性，测试和验证则鲁棒性
        parser.add_argument('--noise_type', type=str,default=None, help='such as jpg, blur and resize')
        
        parser.add_argument('--patch_num', type=int, default=3, help='scale images to this size, used in detection method RPTC')
        
        self.initialized = True

        return parser

    def gather_options(self):
        # initialize parser with basic options
        #Python 的 条件语句（如 if）不会为变量创建局部作用域。换句话说，变量在 if 语句中定义后，仍然属于外部的作用域（此处为全局作用域）
        if not self.initialized:
            #这个参数就是展示参数的 默认值
            parser = argparse.ArgumentParser(
                formatter_class=argparse.ArgumentDefaultsHelpFormatter)
            parser = self.initialize(parser)

        # 解析已知参数，忽略未知参数，返回 (已知参数, 未知参数) 的元组，不会因为出现未知参数而报错
        #TODO
        opt, _ = parser.parse_known_args()
        self.parser = parser

        return parser.parse_args()

    def print_options(self, opt):
        message = ''
        message += '----------------- Options ---------------\n'
        for k, v in sorted(vars(opt).items()):
            comment = ''
            default = self.parser.get_default(k)
            if v != default:
                comment = '\t[default: %s]' % str(default)
            message += '{:>25}: {:<30}{}\n'.format(str(k), str(v), comment)
        message += '----------------- End -------------------'
        print(message)

        # save to the disk
        
        file_name = os.path.join(opt.results_dir, f'{opt.noise_type}opt.txt')
        with open(file_name, 'wt') as opt_file:
            opt_file.write(message)
            opt_file.write('\n')

    def parse(self, print_options=True):

        opt = self.gather_options()
        #TODO存疑这是什么模式
        opt.isTrain = False   # train or test
        opt.isVal = False   # 测试模式
        
        # result dir, save results and opt
        opt.results_dir=f"./results"
        util.mkdir(opt.results_dir)



        if print_options:
            self.print_options(opt)



        # additional

        opt.rz_interp = opt.rz_interp.split(',')
        opt.blur_sig = [float(s) for s in opt.blur_sig.split(',')]
        opt.jpg_method = opt.jpg_method.split(',')
        opt.jpg_qual = [int(s) for s in opt.jpg_qual.split(',')]

        if len(opt.jpg_qual) == 2:
            opt.jpg_qual = list(range(opt.jpg_qual[0], opt.jpg_qual[1] + 1))
        elif len(opt.jpg_qual) > 2:
            raise ValueError("Shouldn't have more than 2 values for --jpg_qual.")

        self.opt = opt
        return self.opt
