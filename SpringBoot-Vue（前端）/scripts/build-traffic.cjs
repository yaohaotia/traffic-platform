const { spawnSync } = require('child_process')
const path = require('path')
const major = Number(process.versions.node.split('.')[0])
const flags = major >= 17 ? ['--openssl-legacy-provider'] : []
const result = spawnSync(process.execPath, flags.concat([require.resolve('@vue/cli-service/bin/vue-cli-service.js'), 'build', '--mode', 'traffic', '--dest', 'dist-traffic']), {
  cwd: path.resolve(__dirname, '..'), stdio: 'inherit', env: Object.assign({}, process.env, { NODE_ENV: 'production' })
})
if (result.error) throw result.error
process.exit(result.status === null ? 1 : result.status)
