import request from '../utils/request'

// 获取菜单列表
export function report(data) { return request({ url: '/user/report', method: 'post', data }) }

export function getViolationTypes(status) { return request({ url: '/getViolationTypes/'+status, method: 'get' }) }

export function getReports(data) { return request({ url: '/user/report', method: 'get', params: data }) }


export function updateReportStatus(newD) {
    const data = new URLSearchParams();
    data.append('status', newD.status);
    return request({
        url: '/inspector/report/status/' + newD.id, 
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        method: 'put',
        data: data
    })
}

// 获取虚假报告详情
export function getReportDetails() {
  return request({
    url: '/user/report/fasleReports',
    method: 'get'
  })
}


// // 获取操作日志
// export function operateLog(data) { return request({ url: '/operate/logs', method: 'get', params: data })}

