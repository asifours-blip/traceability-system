const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  transpileDependencies: true,
  devServer: {
    port: 8011,
    proxy: {
      '/api': {
        target: 'http://localhost:8010',
        changeOrigin: true,
        pathRewrite: { '^/api': '/api' }
      }
    }
  },
  configureWebpack: {
    resolve: {
      alias: {
        '@': require('path').resolve(__dirname, 'src')
      }
    }
  }
})