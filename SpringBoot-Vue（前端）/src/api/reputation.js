import request from '../utils/request'

export function getReputation() {
  return request({
    url: '/user/reputation',
    method: 'get'
  })
}