import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'

// "preview" mode bundles everything into one HTML file so it can be opened as a web page
export default defineConfig(({ mode }) => ({
  plugins: mode === 'preview' ? [react(), viteSingleFile()] : [react()],
  base: './',
  build: { outDir: mode === 'preview' ? 'dist-preview' : 'dist' },
}))
