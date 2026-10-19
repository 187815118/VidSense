/** Cancel stale/account-scoped responses; UI reads SQL state, never invents demo memories. */
export function createMemoryReader({ request, update }) {
  let version = 0
  let controller
  const clear = () => {
    version += 1
    controller?.abort()
    update({ loading: false, enabled: null, items: [], error: '' })
  }
  const load = async ({ authenticated, demo, videoId } = {}) => {
    clear()
    if (!authenticated || demo) return
    const current = version
    controller = new AbortController()
    update({ loading: true, enabled: null, items: [], error: '' })
    try {
      const params = new URLSearchParams({ limit: '20' })
      if (videoId != null) params.set('videoId', String(videoId))
      const response = await request(`/analysis/memory?${params}`, { signal: controller.signal })
      if (!response.ok) throw new Error('记忆记录暂时无法加载，请稍后重试')
      const body = await response.json()
      if (typeof body?.enabled !== 'boolean' || !Array.isArray(body.items)) throw new Error('记忆接口返回格式异常')
      if (current !== version) return
      update({ loading: false, enabled: body.enabled, items: body.enabled ? body.items : [], error: '' })
    } catch (error) {
      if (current !== version || error.name === 'AbortError') return
      update({ loading: false, enabled: null, items: [], error: error.message || '记忆加载失败' })
    }
  }
  return { load, clear }
}
