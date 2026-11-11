<template>
  <section v-loading="busy">
    <div class="panel"><h2>创建平台账号</h2><el-form inline><el-form-item label="账号"><el-input v-model="user.username" /></el-form-item><el-form-item label="初始密码"><el-input v-model="user.initialPassword" type="password" autocomplete="new-password" /></el-form-item><el-form-item label="角色"><el-select v-model="user.roleIds" multiple><el-option v-for="role in catalog.roles" :key="role.id" :label="role.code" :value="role.id" /></el-select></el-form-item><el-form-item label="区域"><el-select v-model="user.regionCodes" multiple><el-option v-for="region in catalog.regions" :key="region.code" :label="region.name" :value="region.code" /></el-select></el-form-item><el-button type="primary" @click="createUser">创建账号</el-button></el-form><p class="muted small">密码至少12位。权限调整或密码重置会使旧登录会话失效。</p></div>
    <div class="panel"><h2>账户与区域授权</h2><el-button @click="load">刷新</el-button><el-table :data="users"><el-table-column prop="id" label="编号" width="75" /><el-table-column prop="username" label="账号" /><el-table-column label="角色"><template slot-scope="s">{{ roleNames(s.row.roleIds) }}</template></el-table-column><el-table-column label="区域"><template slot-scope="s">{{ s.row.regionCodes.join(' / ') }}</template></el-table-column><el-table-column label="状态"><template slot-scope="s">{{ s.row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="操作" min-width="220"><template slot-scope="s"><el-button v-if="s.row.id !== actor.userId" type="text" @click="openAccess(s.row)">调整授权</el-button><el-button type="text" @click="resetPassword(s.row)">重置密码</el-button><el-button v-if="s.row.id !== actor.userId" type="text" @click="toggle(s.row)">{{ s.row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column></el-table><div class="pagination"><el-button :disabled="page===1" @click="page--;load()">上一页</el-button><el-button :disabled="users.length<20" @click="page++;load()">下一页</el-button></div></div>
    <div class="panel"><h2>区域与角色设置</h2><el-form inline><el-input v-model="region.code" placeholder="区域代码，如 NORTH" /><el-input v-model="region.name" placeholder="区域名称" /><el-button @click="createRegion">新增区域</el-button></el-form><el-form inline class="toolbar"><el-input v-model="role.code" placeholder="角色代码，如 REVIEWER" /><el-select v-model="role.permissionCodes" multiple filterable placeholder="选择明确授权的能力"><el-option v-for="permission in catalog.permissions" :key="permission.code" :label="permission.code" :value="permission.code" /></el-select><el-button @click="createRole">新增角色</el-button></el-form></div>
    <el-dialog title="调整账号授权" :visible.sync="accessVisible"><p>{{ editing.username }}</p><el-select v-model="editing.roleIds" multiple><el-option v-for="item in catalog.roles" :key="item.id" :label="item.code" :value="item.id" /></el-select><el-select v-model="editing.regionCodes" multiple><el-option v-for="item in catalog.regions" :key="item.code" :label="item.name" :value="item.code" /></el-select><el-button slot="footer" type="primary" @click="saveAccess">保存并使旧会话失效</el-button></el-dialog>
  </section>
</template>
<script>
import { api, message } from './client'
export default {
  props: { actor: { type: Object, required: true } },
  data() { return { busy: false, page: 1, users: [], catalog: { roles: [], regions: [], permissions: [] }, user: { username: '', initialPassword: '', roleIds: [], regionCodes: [] }, region: { code: '', name: '' }, role: { code: '', permissionCodes: [] }, accessVisible: false, editing: {} } },
  created() { this.load() },
  methods: {
    async work(action) { if (this.busy) return; this.busy = true; try { await action() } catch (e) { if (e !== 'cancel' && e !== 'close') this.$message.error(message(e)) } finally { this.busy = false } },
    async fetch() { this.catalog = await api.get('/identity/catalog'); this.users = (await api.get('/identity/users', { page: this.page })).items }, load() { this.work(() => this.fetch()) },
    roleNames(ids) { return (ids || []).map(id => (this.catalog.roles.find(r => r.id === id) || {}).code || id).join(' / ') },
    createUser() { this.work(async () => { await api.post('/identity/users', this.user); this.user.initialPassword = ''; this.user.username = ''; this.$message.success('新账号已创建，请安全交付初始密码'); await this.fetch() }) },
    createRegion() { this.work(async () => { await api.post('/identity/regions', this.region); this.region = { code: '', name: '' }; await this.fetch() }) },
    createRole() { this.work(async () => { await api.post('/identity/roles', this.role); this.role = { code: '', permissionCodes: [] }; await this.fetch() }) },
    toggle(row) { this.work(async () => { await api.put(`/identity/users/${row.id}/enabled`, { enabled: !row.enabled }); await this.fetch() }) },
    openAccess(row) { this.editing = { id: row.id, username: row.username, roleIds: row.roleIds.slice(), regionCodes: row.regionCodes.slice() }; this.accessVisible = true },
    saveAccess() { this.work(async () => { await api.put(`/identity/users/${this.editing.id}/access`, this.editing); this.accessVisible = false; await this.fetch() }) },
    resetPassword(row) { this.work(async () => { const input = await this.$prompt(`为 ${row.username} 设置至少12位的新密码`, '受控密码重置', { inputType: 'password' }); await api.put(`/identity/users/${row.id}/password`, { password: input.value }); this.$message.success('密码已重置，原会话已失效') }) }
  }
}
</script>
