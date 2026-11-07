import request from '@/utils/request'

// 获取银行账户列表
export function getBankAccounts() {
  return request({
    url: '/user/bank',
    method: 'get'
  })
}

// 添加银行账户
export function addBankAccount(data) {
  return request({
    url: '/user/bank',
    method: 'post',
    data
  })
}

// 更新银行账户
export function updateBankAccount(id, data) {
  return request({
    url: `/user/bank/${id}`,
    method: 'put',
    data
  })
}

// 删除银行账户
export function deleteBankAccount(id) {
  return request({
    url: `/user/bank/${id}`,
    method: 'delete'
  })
}

// 设置默认银行账户
export function setDefaultBankAccount(id) {
  return request({
    url: `/user/bank/${id}`,
    method: 'patch'
  })
}


// 获得默认银行账户
export function getDefaultCard() {
  return request({
    url: `/user/bank/default`,
    method: 'get'
  })
}