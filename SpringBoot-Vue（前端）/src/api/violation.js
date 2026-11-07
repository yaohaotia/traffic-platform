import request from '../utils/request'

export function changeViolation(status,id) {
  return request({
    url: '/admin/violationTypes/'+status,
    method: 'patch',
    params:{id}
  })
}

export function changeReward(reward,id) {
  return request({
    url: '/admin/violationTypes/money/'+reward,
    method: 'patch',
    params:{id}
  })
}

