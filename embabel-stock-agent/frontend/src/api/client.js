/** POST + SSE reader (EventSource only supports GET, so we parse the stream ourselves). */
export async function streamPost(url, body, onEvent, signal) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal
  })
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }
  if (!response.body) {
    throw new Error('Streaming is not supported by this browser')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const processBlock = (block) => {
    const lines = block.split('\n')
    const eventLine = lines.find((line) => line.startsWith('event:'))
    const data = lines
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
    if (!data) return
    try {
      onEvent({ event: eventLine ? eventLine.slice(6).trim() : 'message', data: JSON.parse(data) })
    } catch {
      onEvent({ event: 'message', data: { type: 'token', text: data } })
    }
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let index
    while ((index = buffer.indexOf('\n\n')) !== -1) {
      processBlock(buffer.slice(0, index))
      buffer = buffer.slice(index + 2)
    }
  }
  if (buffer.trim()) processBlock(buffer)
}

export async function apiGet(path) {
  const response = await fetch(path)
  if (!response.ok) throw new Error(`${path}: ${response.status}`)
  return response.json()
}

export async function apiPost(path, body) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!response.ok) throw new Error(`${path}: ${response.status}`)
  return response.json()
}

export async function apiDelete(path) {
  const response = await fetch(path, { method: 'DELETE' })
  if (!response.ok) throw new Error(`${path}: ${response.status}`)
}
