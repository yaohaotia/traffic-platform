import request from '../utils/request'

// 获取管理员列表
export function adminList(data) { return request({ url: '/admins', method: 'get', params: data })}

// 获取管理员详情
export function getAdmin(id) { return request({ url: '/admin/' + id, method: 'get' })}

// 添加管理员
export function createAdmin(data) { return request({ url: '/addAdmin', method: 'post', data })}

// 编辑管理员
export function editAdmin(data) { return request({ url: '/admin', method: 'put', data })}

// 删除管理员
export function delAdmin(id) { return request({ url: '/admin/' + id, method: 'delete' })}

// echarts
export function getDashboardData() { return request({ url: '/admin/echarts', method: 'get' })}

// 修改密码
export function changePwd(data) { return request({ url: 'admin/changePassword', method: 'post', data })}