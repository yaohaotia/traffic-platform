import request from '@/utils/request'

// 获取用户数据
export function getUserData() {
  return request({
    url: '/user/account',
    method: 'get'
  })
}

// 提现申请
export function withdraw(data) {
  return request({
    url: '/user/withdrawals',
    method: 'post',
    data
  })
}

// 获取所有提现申请
export function getWithdrawals(data) {
  return request({
    url: '/user/withdrawals/list',
    method: 'post',
      headers: {
          'Content-Type': 'application/json'
        },
    data
  })
}

// 更新提现申请状态（管理员审核提现）
export function updateWithdrawalStatus(data) {

  return request({
    url: '/admin/withdrawals/status',
    method: 'put',
    data
  })
}




