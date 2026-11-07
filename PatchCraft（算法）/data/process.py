import torch
import torchvision
import torchvision.transforms as transforms
import cv2
import numpy as np
from io import BytesIO
from PIL import Image
from random import random, choice
import copy

from scipy.ndimage.filters import gaussian_filter
import torch.nn.functional as F
import torch.distributed as dist
import torch.nn as nn
import torchvision.transforms.functional as TF




def data_augment(img, opt):
    img = np.array(img)

    if random() < opt.blur_prob:

        sig = sample_continuous(opt.blur_sig)
        gaussian_blur(img, sig)

    if random() < opt.jpg_prob:
        method = sample_discrete(opt.jpg_method)
        qual = sample_discrete(opt.jpg_qual)
        img = jpeg_from_key(img, qual, method)



    return Image.fromarray(img)


def sample_continuous(s):
    if len(s) == 1:
        return s[0]
    if len(s) == 2:
        rg = s[1] - s[0]
        return random() * rg + s[0]
    raise ValueError("Length of iterable s should be 1 or 2.")


def sample_discrete(s):
    if len(s) == 1:
        return s[0]
    return choice(s)


def gaussian_blur_gray(img, sigma):
    if len(img.shape) == 3:
        img_blur = np.zeros_like(img)
        for i in range(img.shape[2]):
            img_blur[:, :, i] = gaussian_filter(img[:, :, i], sigma=sigma)
    else:
        img_blur = gaussian_filter(img, sigma=sigma)
    return img_blur

def gaussian_blur(img, sigma):
    gaussian_filter(img[:,:,0], output=img[:,:,0], sigma=sigma)
    gaussian_filter(img[:,:,1], output=img[:,:,1], sigma=sigma)
    gaussian_filter(img[:,:,2], output=img[:,:,2], sigma=sigma)




def cv2_jpg_gray(img, compress_val):
    encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), compress_val]
    result, encimg = cv2.imencode('.jpg', img, encode_param)
    decimg = cv2.imdecode(encimg, 0)
    return decimg

def cv2_jpg(img, compress_val):
    img_cv2 = img[:,:,::-1]
    encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), compress_val]
    result, encimg = cv2.imencode('.jpg', img_cv2, encode_param)
    decimg = cv2.imdecode(encimg, 1)
    return decimg[:,:,::-1]


def pil_jpg(img, compress_val):
    out = BytesIO()
    img = Image.fromarray(img)
    img.save(out, format='jpeg', quality=compress_val)
    img = Image.open(out)
    # load from memory before ByteIO closes
    img = np.array(img)
    out.close()
    return img


jpeg_dict = {'cv2': cv2_jpg, 'pil': pil_jpg}
def jpeg_from_key(img, compress_val, key):
    method = jpeg_dict[key]
    return method(img, compress_val)





def sample_discrete(s):
    if len(s) == 1:
        return s[0]
    return choice(s)

rz_dict = {'bilinear': Image.BILINEAR,
           'bicubic': Image.BICUBIC,
           'lanczos': Image.LANCZOS,
           'nearest': Image.NEAREST}
def custom_resize(img, opt):

    interp = sample_discrete(opt.rz_interp)
    return TF.resize(img, opt.loadSize, interpolation=rz_dict[interp])



def processing(img, opt, name):
    if opt.isTrain:
        crop_func = transforms.RandomCrop(opt.CropSize)
    elif opt.no_crop:
        crop_func = transforms.Lambda(lambda img: img)
    else:
        crop_func = transforms.CenterCrop(opt.CropSize)

    if opt.isTrain and not opt.no_flip:
        flip_func = transforms.RandomHorizontalFlip()
    else:
        flip_func = transforms.Lambda(lambda img: img)
    if not opt.isTrain and opt.no_resize:
        rz_func = transforms.Lambda(lambda img: img)
    else:
        rz_func = transforms.Lambda(lambda img: custom_resize(img, opt))
    trans = transforms.Compose([
                rz_func,
                transforms.Lambda(lambda img: data_augment(img, opt) if opt.isTrain else img),
                crop_func,
                flip_func,
                transforms.ToTensor(),
                transforms.Normalize(mean=MEAN[name], std=STD[name]),
                ])
    return trans(img)


MEAN = {
    "imagenet":[0.485, 0.456, 0.406],
    "clip":[0.48145466, 0.4578275, 0.40821073]
}

STD = {
    "imagenet":[0.229, 0.224, 0.225],
    "clip":[0.26862954, 0.26130258, 0.27577711]
}





def normlize_np(img):
    img -= img.min()
    if img.max()!=0: img /= img.max()
    return img * 255.   





def ED(img):
    r1,r2 = img[:,0:-1,:], img[:,1::,:]
    r3,r4 = img[:,:,0:-1], img[:,:,1::]
    r5,r6 = img[:,0:-1,0:-1], img[:,1::,1::]
    r7,r8 = img[:,0:-1,1::], img[:,1::,0:-1]
    s1 = torch.sum(torch.abs(r1 - r2)).item()
    s2 = torch.sum(torch.abs(r3 - r4)).item()
    s3 = torch.sum(torch.abs(r5 - r6)).item()
    s4 = torch.sum(torch.abs(r7 - r8)).item() 
    return s1+s2+s3+s4


def processing_RPTC(img, opt):
    
    rate = opt.patch_num
        
    num_block = int(pow(2,rate))
    patchsize = int(opt.loadSize/num_block)
    randomcrop = torchvision.transforms.RandomCrop(patchsize)
    
    minsize = min(img.size)
    if minsize < patchsize:
        img = torchvision.transforms.Resize((patchsize,patchsize))(img)
    
    img = torchvision.transforms.ToTensor()(img)
    # img = torchvision.transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])(img)
    imgori = img.clone().unsqueeze(0)
    img_template = torch.zeros(3, opt.loadSize, opt.loadSize)
    img_crops = []
    for i in range(num_block*num_block*3):
        cropped_img = randomcrop(img)
        texture_rich = ED(cropped_img)
        img_crops.append([cropped_img,texture_rich])

    img_crops = sorted(img_crops,key=lambda x:x[1])

    count = 0
    for ii in range(num_block):
        for jj in range(num_block):
            img_template[:,ii*patchsize:(ii+1)*patchsize,jj*patchsize:(jj+1)*patchsize] = img_crops[count][0]
            count += 1
    img_poor = img_template.clone().unsqueeze(0)

    count = -1
    for ii in range(num_block):
        for jj in range(num_block):
            img_template[:,ii*patchsize:(ii+1)*patchsize,jj*patchsize:(jj+1)*patchsize] = img_crops[count][0]
            count -= 1
    img_rich = img_template.clone().unsqueeze(0)
    img = torch.cat((img_poor,img_rich),0)
    
    return img
