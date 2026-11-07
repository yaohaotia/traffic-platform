module.exports = {
    ...(process.env.VUE_APP_TRAFFIC === 'true' ? {
        pages: { index: { entry: 'src/traffic/main.js', template: 'public/traffic.html', title: '交通线索智能审核与激励平台' } }
    } : {}),
    lintOnSave: false,  // 如果为false，就是取消eslint规则的检查
    publicPath: './',
    assetsDir: 'static',
    productionSourceMap: false,
    devServer: {
        // 设置代理
        proxy: {
            '/api/v1': { target: 'http://localhost:8090', changeOrigin: true },
            '/spba-api': {
                target: 'http://localhost:8082',
                changeOrigin: true,
                pathRewrite: {
                    '^/': ''
                }
            }
        }
    },
    // vue.config.js
    configureWebpack: {
		devtool: "source-map",
	}
}

