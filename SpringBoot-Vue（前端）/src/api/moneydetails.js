import request from '../utils/request'


export function getBalanceDetails(params) {
    return request({
        url: '/user/balanceChangeLogs',
        method: 'get',
        params
    })
}

export function balanceChangeSummary() {
    return request({
        url: '/user/balanceChangeLogs/balanceChangeSummary',
        method: 'get'
    })
}


