import { readFileSync } from 'node:fs'

import { defineConfig } from 'blume'

const properties = readFileSync(new URL('../../gradle.properties', import.meta.url), 'utf8')
const version = /^VERSION_NAME=(.+)$/m.exec(properties)?.[1]?.trim()
if (!version) throw new Error('VERSION_NAME is missing from gradle.properties')

export default defineConfig({
  title: 'Casement',
  description: 'An MCP Apps host for Compose Multiplatform: Android, iOS, web and desktop.',
  logo: '/logo.svg',
  github: {
    owner: 'jantursky',
    repo: 'casement',
    dir: 'apps/docs',
  },
  content: {
    root: 'docs',
  },
  theme: {
    accent: '#5b4be8',
    radius: 'lg',
    mode: 'system',
  },
  lastModified: 'git',
  variables: {
    version,
  },
})
